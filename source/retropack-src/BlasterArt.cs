using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Text;

// LaserTag blasters: original 3D item models (barrel towards -Z = forward in first person),
// one per team colour, glowing energy cells and muzzle; plus shot / hit / overheat sounds.
public static class BlasterArt
{
    const int SR = 44100;

    public static void Generate(string root, string wavDir)
    {
        string tex = Path.Combine(root, "assets/neyrocraft/textures/item");
        string mod = Path.Combine(root, "assets/neyrocraft/models/item");
        string items = Path.Combine(root, "assets/neyrocraft/items");
        Directory.CreateDirectory(tex); Directory.CreateDirectory(mod); Directory.CreateDirectory(items);
        Metal(Path.Combine(tex, "blaster_body.png"), Color.FromArgb(60, 64, 78), 8);
        Metal(Path.Combine(tex, "blaster_light.png"), Color.FromArgb(210, 214, 226), 10);
        Metal(Path.Combine(tex, "blaster_dark.png"), Color.FromArgb(26, 27, 34), 6);
        Glow(Path.Combine(tex, "blaster_glow_red.png"), Color.FromArgb(255, 60, 50));
        Glow(Path.Combine(tex, "blaster_glow_blue.png"), Color.FromArgb(70, 170, 255));
        Glow(Path.Combine(tex, "blaster_lens.png"), Color.FromArgb(120, 255, 230));
        foreach (string team in new[] { "red", "blue" })
        {
            File.WriteAllText(Path.Combine(mod, "blaster_" + team + ".json"), Model(team), new UTF8Encoding(false));
            File.WriteAllText(Path.Combine(items, "blaster_" + team + ".json"),
                "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"neyrocraft:item/blaster_" + team + "\"}}", new UTF8Encoding(false));
        }
        Directory.CreateDirectory(wavDir);
        Write(wavDir, "blaster_shot1", Shot(1.0, 3));
        Write(wavDir, "blaster_shot2", Shot(1.12, 5));
        Write(wavDir, "blaster_hit", Hit());
        Write(wavDir, "blaster_overheat", Overheat());
    }

    static int Cl(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

    static void Metal(string path, Color c, int amp)
    {
        var b = new Bitmap(16, 16, PixelFormat.Format32bppArgb); var r = new Random(c.R + c.G);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
            {
                int d = r.Next(-amp, amp);
                if (y == 0 || x == 0) d += 18;                 // lit edge
                if (y == 15 || x == 15) d -= 22;               // shadow edge
                if (y == 8) d -= 12;                           // panel seam
                b.SetPixel(x, y, Color.FromArgb(255, Cl(c.R + d), Cl(c.G + d), Cl(c.B + d)));
            }
        b.Save(path, ImageFormat.Png); b.Dispose();
    }

    static void Glow(string path, Color c)
    {
        var b = new Bitmap(16, 16, PixelFormat.Format32bppArgb);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
            {
                double t = Math.Max(0, 1 - Math.Sqrt((x - 7.5) * (x - 7.5) + (y - 7.5) * (y - 7.5)) / 10.6);
                b.SetPixel(x, y, Color.FromArgb(255, Cl((int)(c.R * 0.7 + 255 * 0.45 * t)), Cl((int)(c.G * 0.7 + 255 * 0.45 * t)), Cl((int)(c.B * 0.7 + 255 * 0.45 * t))));
            }
        b.Save(path, ImageFormat.Png); b.Dispose();
    }

    static string F(double[] a)
    {
        var inv = System.Globalization.CultureInfo.InvariantCulture; var s = new string[a.Length];
        for (int i = 0; i < a.Length; i++) s[i] = a[i].ToString("0.###", inv);
        return "[" + string.Join(",", s) + "]";
    }

    static string Box(double x1, double y1, double z1, double x2, double y2, double z2, string tex, bool glow)
    {
        var sb = new StringBuilder("{\"from\":" + F(new[] { x1, y1, z1 }) + ",\"to\":" + F(new[] { x2, y2, z2 }));
        if (glow) sb.Append(",\"light_emission\":15");
        sb.Append(",\"faces\":{");
        string[] f = { "north", "south", "east", "west", "up", "down" };
        for (int i = 0; i < f.Length; i++) { if (i > 0) sb.Append(','); sb.Append('"').Append(f[i]).Append("\":{\"uv\":[0,0,16,16],\"texture\":\"#").Append(tex).Append("\"}"); }
        return sb.Append("}}").ToString();
    }

    static string Model(string team)
    {
        var e = new List<string>
        {
            Box(6, 7, 4, 10, 11, 14, "body", false),          // main body
            Box(6.5, 11, 5, 9.5, 11.6, 13, "light", false),   // top plate
            Box(5.6, 7.5, 5, 6, 10.5, 12, "glow", true),      // left energy cell
            Box(10, 7.5, 5, 10.4, 10.5, 12, "glow", true),    // right energy cell
            Box(7, 8.2, -1, 9, 10.2, 4, "dark", false),       // barrel
            Box(6.5, 7.7, 1, 9.5, 10.7, 2, "glow", true),     // barrel ring
            Box(7.2, 8.4, -1.6, 8.8, 10, -1, "glow", true),   // muzzle
            Box(7.25, 11.6, 6, 8.75, 12.9, 11, "dark", false),// scope
            Box(7.45, 11.8, 5.6, 8.55, 12.7, 6, "lens", true),// scope lens
            Box(6.6, 2, 10, 9.4, 7, 13, "dark", false),       // grip
            Box(6.5, 1.9, 9.4, 9.5, 2.6, 13.1, "light", false), // grip cap (a hair bigger than the grip: no z-fighting)
            Box(7.4, 5.4, 7.5, 8.6, 7, 9.6, "dark", false),   // trigger guard
            Box(6.2, 7.4, 14, 9.8, 10.6, 16, "light", false), // back plate
        };
        string glowTex = team == "red" ? "neyrocraft:item/blaster_glow_red" : "neyrocraft:item/blaster_glow_blue";
        return "{\"textures\":{\"body\":\"neyrocraft:item/blaster_body\",\"light\":\"neyrocraft:item/blaster_light\","
            + "\"dark\":\"neyrocraft:item/blaster_dark\",\"glow\":\"" + glowTex + "\",\"lens\":\"neyrocraft:item/blaster_lens\","
            + "\"particle\":\"neyrocraft:item/blaster_body\"},"
            + "\"elements\":[" + string.Join(",", e) + "],"
            + "\"display\":{"
            + "\"firstperson_righthand\":{\"rotation\":[0,5,0],\"translation\":[-1,2.5,0],\"scale\":[0.85,0.85,0.85]},"
            + "\"firstperson_lefthand\":{\"rotation\":[0,-5,0],\"translation\":[-1,2.5,0],\"scale\":[0.85,0.85,0.85]},"
            // held by the middle of the grip, barrel level (72 = 90 minus the 18 degrees the arm is raised when holding an item)
            + "\"thirdperson_righthand\":{\"rotation\":[72,0,0],\"translation\":[0,1.75,1.4],\"scale\":[0.85,0.85,0.85]},"
            + "\"thirdperson_lefthand\":{\"rotation\":[72,0,0],\"translation\":[0,1.75,1.4],\"scale\":[0.85,0.85,0.85]},"
            + "\"gui\":{\"rotation\":[15,-135,0],\"translation\":[0,0,0],\"scale\":[0.95,0.95,0.95]},"
            + "\"ground\":{\"rotation\":[0,0,0],\"translation\":[0,2,0],\"scale\":[0.5,0.5,0.5]},"
            + "\"fixed\":{\"rotation\":[0,-90,0],\"scale\":[1,1,1]}}}";
    }

    // ------------------------------------------------------------ sounds

    static double[] Shot(double pitch, int seed)
    {
        double len = 0.32; int n = (int)(len * SR); var o = new double[n]; var r = new Random(seed); double ph = 0, ph2 = 0, lp = 0;
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR, k = t / len;
            double f = (1900 * Math.Exp(-t * 14) + 180) * pitch;       // falling "pew"
            ph += f / SR; ph2 += f * 1.5 / SR;
            double x = Math.Sin(2 * Math.PI * ph) * 0.7 + Math.Sin(2 * Math.PI * ph2) * 0.25 + (ph % 1 < 0.5 ? 0.15 : -0.15);
            double nz = r.NextDouble() * 2 - 1; lp += 0.3 * (nz - lp);
            x += lp * 0.5 * Math.Exp(-t * 40);                          // the crack at the start
            o[i] = x * Math.Min(1, t / 0.002) * Math.Exp(-t * 9);
        }
        return Norm(o, 0.9);
    }

    static double[] Hit()
    {
        double len = 0.4; int n = (int)(len * SR); var o = new double[n]; var r = new Random(8); double ph = 0, hp = 0, prev = 0;
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR;
            double nz = r.NextDouble() * 2 - 1; hp = 0.7 * (hp + nz - prev); prev = nz;
            double buzz = Math.Sign(Math.Sin(2 * Math.PI * 95 * t)) * 0.3 * (r.NextDouble() < 0.6 ? 1 : 0);
            ph += (620 + 300 * Math.Sin(t * 90)) / SR;
            o[i] = (hp * 0.5 + buzz + Math.Sin(2 * Math.PI * ph) * 0.4) * Math.Exp(-t * 8);
        }
        return Norm(o, 0.85);
    }

    static double[] Overheat()
    {
        double len = 1.1; int n = (int)(len * SR); var o = new double[n]; var r = new Random(9); double lp = 0, ph = 0;
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR;
            double nz = r.NextDouble() * 2 - 1; lp += 0.5 * (nz - lp);
            ph += (900 - 600 * t / len) / SR;
            o[i] = ((nz - lp) * 0.6 + Math.Sin(2 * Math.PI * ph) * 0.2) * Math.Min(1, t / 0.03) * Math.Exp(-t * 2.5);
        }
        return Norm(o, 0.8);
    }

    static double[] Norm(double[] o, double peak)
    {
        double p = 1e-9; foreach (double v in o) p = Math.Max(p, Math.Abs(v));
        for (int i = 0; i < o.Length; i++) o[i] = o[i] / p * peak;
        int f = 300; for (int i = 0; i < f && i < o.Length; i++) o[o.Length - 1 - i] *= i / (double)f;
        return o;
    }

    static void Write(string dir, string name, double[] o)
    {
        using (var w = new BinaryWriter(File.Create(Path.Combine(dir, name + ".wav"))))
        {
            int len = o.Length * 2;
            w.Write(new[]{'R','I','F','F'}); w.Write(36 + len); w.Write(new[]{'W','A','V','E'});
            w.Write(new[]{'f','m','t',' '}); w.Write(16); w.Write((short)1); w.Write((short)1); w.Write(SR); w.Write(SR * 2); w.Write((short)2); w.Write((short)16);
            w.Write(new[]{'d','a','t','a'}); w.Write(len);
            foreach (double v in o) w.Write((short)Math.Round(Math.Max(-1, Math.Min(1, v)) * 32767));
        }
    }
}
