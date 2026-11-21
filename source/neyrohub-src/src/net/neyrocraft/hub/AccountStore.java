package net.neyrocraft.hub;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Player accounts for NeyroAuth, stored in accounts.yml.
 * Passwords are hashed with PBKDF2-HMAC-SHA256 and a random salt; plain passwords are never stored.
 * Accounts imported from AuthMe keep their "$SHA$" hash and are upgraded on the next login.
 */
final class AccountStore {

    static final int ITERATIONS = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final File file;
    private final Logger log;
    private YamlConfiguration data;

    record Account(String key, String realName, String hash, String regIp, long regDate, String lastIp, long lastLogin) {}

    AccountStore(File dataFolder, Logger log) {
        this.file = new File(dataFolder, "accounts.yml");
        this.log = log;
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    synchronized Account get(String name) {
        ConfigurationSection s = data.getConfigurationSection("accounts." + key(name));
        if (s == null) return null;
        return new Account(key(name), s.getString("name", name), s.getString("hash"),
                s.getString("reg-ip", ""), s.getLong("reg-date"), s.getString("last-ip", ""), s.getLong("last-login"));
    }

    synchronized boolean exists(String name) {
        return data.isConfigurationSection("accounts." + key(name));
    }

    synchronized String email(String name) { return data.getString("accounts." + key(name) + ".email", ""); }
    synchronized void setEmail(String name, String email) {
        if (!exists(name)) throw new IllegalStateException("Account missing");
        data.set("accounts." + key(name) + ".email", email);
        save();
    }

    synchronized int countByIp(String ip) {
        ConfigurationSection all = data.getConfigurationSection("accounts");
        if (all == null) return 0;
        int n = 0;
        for (String k : all.getKeys(false)) if (ip.equals(all.getString(k + ".reg-ip"))) n++;
        return n;
    }

    synchronized void put(String realName, String hash, String ip) {
        String path = "accounts." + key(realName);
        data.set(path + ".name", realName);
        data.set(path + ".hash", hash);
        data.set(path + ".reg-ip", ip);
        data.set(path + ".reg-date", System.currentTimeMillis());
        save();
    }

    synchronized void putVerified(String realName, String hash, String ip, String email) throws IOException {
        String path="accounts." + key(realName);
        if (exists(realName)) throw new IllegalStateException("Account exists");
        data.set(path+".name", realName);
        data.set(path+".hash", hash);
        data.set(path+".reg-ip", ip);
        data.set(path+".reg-date", System.currentTimeMillis());
        data.set(path+".email", email);
        try { file.getParentFile().mkdirs(); data.save(file); }
        catch (IOException ex) { data.set(path,null); throw ex; }
    }

    synchronized void setHash(String name, String hash) {
        data.set("accounts." + key(name) + ".hash", hash);
        save();
    }

    synchronized void touch(String name, String ip) {
        data.set("accounts." + key(name) + ".last-ip", ip);
        data.set("accounts." + key(name) + ".last-login", System.currentTimeMillis());
        save();
    }

    synchronized boolean remove(String name) {
        if (!exists(name)) return false;
        data.set("accounts." + key(name), null);
        save();
        return true;
    }

    synchronized int size() {
        ConfigurationSection all = data.getConfigurationSection("accounts");
        return all == null ? 0 : all.getKeys(false).size();
    }

    private void save() {
        try {
            file.getParentFile().mkdirs();
            data.save(file);
        } catch (IOException e) {
            log.severe("Could not save accounts.yml: " + e);
        }
    }

    // ---------------------------------------------------------------- hashing

    /** Slow on purpose (~0.1 s): call from an async task. */
    static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] dk = pbkdf2(password, salt, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return "pbkdf2$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(dk);
    }

    /** Slow on purpose: call from an async task. */
    static boolean verify(String password, String stored) {
        if (stored == null) return false;
        try {
            if (stored.startsWith("pbkdf2$")) {
                String[] p = stored.split("\\$");
                int it = Integer.parseInt(p[1]);
                byte[] salt = Base64.getDecoder().decode(p[2]);
                byte[] expected = Base64.getDecoder().decode(p[3]);
                return MessageDigest.isEqual(expected, pbkdf2(password, salt, it));
            }
            if (stored.startsWith("$SHA$")) {      // AuthMe SHA256: $SHA$salt$sha256(sha256(pw)+salt)
                String[] p = stored.split("\\$");
                String salt = p[2];
                String expected = p[3];
                String actual = sha256(sha256(password) + salt);
                return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                        actual.getBytes(StandardCharsets.US_ASCII));
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    static boolean isLegacy(String stored) {
        return stored != null && !stored.startsWith("pbkdf2$");
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // -------------------------------------------------------------- migration

    /** Imports accounts from AuthMe's SQLite database once. Returns the number imported. */
    synchronized int importAuthMe(File authmeDb) {
        if (!authmeDb.exists() || data.getBoolean("imported-authme", false)) return 0;
        int n = 0;
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + authmeDb.getAbsolutePath());
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT username, realname, password, ip, regdate FROM authme")) {
                while (rs.next()) {
                    String user = rs.getString("username");
                    String real = rs.getString("realname");
                    String pw = rs.getString("password");
                    if (user == null || pw == null || pw.isEmpty() || exists(user)) continue;
                    String path = "accounts." + key(user);
                    data.set(path + ".name", real != null && !real.isEmpty() ? real : user);
                    data.set(path + ".hash", pw);
                    data.set(path + ".reg-ip", rs.getString("ip") == null ? "" : rs.getString("ip"));
                    data.set(path + ".reg-date", rs.getLong("regdate"));
                    n++;
                }
            }
            data.set("imported-authme", true);
            save();
        } catch (Exception e) {
            log.warning("AuthMe import failed: " + e);
        }
        return n;
    }
}
