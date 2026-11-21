package net.neyrocraft.hub;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** SMTP test sender; secrets are read from the server process environment. */
final class NeyroMail {
    private final NeyroHub plugin;
    NeyroMail(NeyroHub plugin) { this.plugin=plugin; }

    boolean command(CommandSender sender, String[] args) {
        if (!sender.hasPermission("neyrohub.admin")) return true;
        if (args.length != 2 || !args[0].equalsIgnoreCase("test") || !args[1].matches("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")) {
            sender.sendMessage("Использование: /neyromail test <адрес>"); return true;
        }
        String secret=mailSecret();
        if (secret == null || secret.isBlank()) { sender.sendMessage("Почта не подключена: нужен пароль приложения Mail.ru в NEYRO_MAIL_PASSWORD."); return true; }
        String recipient=args[1];
        sender.sendMessage("Отправляю проверочное письмо...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String code=String.format("%06d",new SecureRandom().nextInt(1_000_000));
                send(recipient,secret,code,true);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("Проверочное письмо передано почтовому серверу. Проверь ящик."));
            } catch (Exception ex) {
                plugin.getLogger().warning("NeyroMail SMTP delivery failed: " + ex.getClass().getSimpleName());
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("Не удалось отправить письмо. Проверь настройки почтового приложения и соединение."));
            }
        });
        return true;
    }

    static String mailSecret() {
        String value=System.getenv("NEYRO_MAIL_PASSWORD");
        try {
            Process p=new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "[Environment]::GetEnvironmentVariable('NEYRO_MAIL_PASSWORD','User')").redirectErrorStream(true).start();
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue()!=0) { p.destroyForcibly(); return null; }
            String result=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
            return result.isEmpty()?value:result;
        } catch (IOException | InterruptedException e) { if(e instanceof InterruptedException) Thread.currentThread().interrupt(); return value; }
    }

    static void send(String to,String secret,String code) throws IOException { send(to,secret,code,false); }
    private static void send(String to,String secret,String code,boolean test) throws IOException {
        String from="d-kushin@mail.ru";
        try (SSLSocket s=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket("smtp.mail.ru",465)) {
            s.setSoTimeout(15000);
            var params=s.getSSLParameters(); params.setEndpointIdentificationAlgorithm("HTTPS"); s.setSSLParameters(params); s.startHandshake();
            var in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));
            var out=new BufferedWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.UTF_8));
            expect(in,220); line(out,"EHLO neyrocraft.space"); expect(in,250);
            line(out,"AUTH LOGIN"); expect(in,334);
            line(out,Base64.getEncoder().encodeToString(from.getBytes(StandardCharsets.UTF_8))); expect(in,334);
            line(out,Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8))); expect(in,235);
            line(out,"MAIL FROM:<"+from+">"); expect(in,250); line(out,"RCPT TO:<"+to+">"); expect(in,250);
            line(out,"DATA"); expect(in,354);
            String subject=Base64.getEncoder().encodeToString("NEYRO-CORP: проверочный код".getBytes(StandardCharsets.UTF_8));
            String body=test ? "Здравствуйте!\n\nЭто проверочное письмо NEYRO-CORP. Тестовый код: "+code+"\nКод не меняет аккаунт.\nNeyroCraft" : "Здравствуйте!\n\nКод NEYRO-CORP: "+code+"\n\nНикому не сообщайте код. Он действует 10 минут.\nNeyroCraft";
            line(out,"From: NEYRO-CORP <"+from+">\r\nTo: <"+to+">\r\nSubject: =?UTF-8?B?"+subject+"?=\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n"+Base64.getMimeEncoder(76,"\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(body.getBytes(StandardCharsets.UTF_8))+"\r\n.");
            expect(in,250); line(out,"QUIT");
        }
    }
    private static void line(BufferedWriter out,String s) throws IOException { out.write(s); out.write("\r\n"); out.flush(); }
    private static void expect(BufferedReader in,int code) throws IOException {
        String s; do { s=in.readLine(); if(s==null || s.length()<3 || !s.startsWith(Integer.toString(code))) throw new IOException("SMTP response rejected"); } while(s.length()>3 && s.charAt(3)=='-');
    }
}
