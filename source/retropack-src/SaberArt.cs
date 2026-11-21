using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Text;

// Energy-sword ("световой меч") item: original 3D model (hilt + glowing core + translucent glow), 8 colours,
// 4 ignition stages each. Built on the diagonal so the vanilla sword hold places it right in the hand.
public static class SaberArt
{
    public static readonly string[] Names = { "red", "blue", "green", "purple", "yellow", "orange", "cyan", "white" };
    static readonly int[] Rgb = { 0xFF2A2A, 0x2A7BFF, 0x2AFF5A, 0xB04BFF, 0xFFE83A, 0xFF8A1E, 0x2AF5FF, 0xF2F2FF };

    public static void Generate(string root)
    {
        string tex = Path.Combine(root, "assets/neyrocraft/textures/item");
        string mod = Path.Combine(root, "assets/neyrocraft/models/item");
        string items = Path.Combine(root, "assets/neyrocraft/items");
        Directory.CreateDirectory(tex); Directory.CreateDirectory(mod); Directory.CreateDirectory(items);
        Hilt(Path.Combine(tex, "saber_hilt.png"));
        for (int i = 0; i < Names.Length; i++)
        {
            Color c = Color.FromArgb(255, (Rgb[i] >> 16) & 255, (Rgb[i] >> 8) & 255, Rgb[i] & 255);
            Core(Path.Combine(tex, "saber_core_" + Names[i] + ".png"), c);
            Glow(Path.Combine(tex, "saber_glow_" + Names[i] + ".png"), c);
            for (int k = 0; k <= 3; k++)
            {
                string id = "saber_" + Names[i] + "_" + k;
                File.WriteAllText(Path.Combine(mod, id + ".json"), Model(Names[i], k), new UTF8Encoding(false));
                File.WriteAllText(Path.Combine(items, id + ".json"),
                    "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"neyrocraft:item/" + id + "\"}}", new UTF8Encoding(false));
            }
        }
    }

    static void Save(Bitmap b, string path) { b.Save(path, ImageFormat.Png); b.Dispose(); }

    static void Hilt(string path)
    {
        var b = new Bitmap(16, 16, PixelFormat.Format32bppArgb);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
            {
                int v;
                // texture v runs along the hilt: emitter (top rows), grip with ridges, button, pommel
                if (y < 3) v = 205 - y * 10;
                else if (y < 12) v = (y % 2 == 0) ? 40 : 150;
                else if (y < 13) v = -1;
                else v = 95 - (y - 13) * 10;
                int shade = x < 5 ? 25 : x > 11 ? -25 : 0;
                Color c = v < 0 ? Color.FromArgb(255, 220, 30, 30) : Color.FromArgb(255, Cl(v + shade), Cl(v + shade), Cl(v + shade + 8));
                b.SetPixel(x, y, c);
            }
        Save(b, path);
    }

    static void Core(string path, Color c)
    {
        var b = new Bitmap(16, 16, PixelFormat.Format32bppArgb);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
            {
                double d = Math.Abs(x - 7.5) / 7.5;                 // 0 centre .. 1 edge
                double t = Math.Max(0, d - 0.45) / 0.55;
                b.SetPixel(x, y, Color.FromArgb(255, Mix(255, c.R, t * 0.7), Mix(255, c.G, t * 0.7), Mix(255, c.B, t * 0.7)));
            }
        Save(b, path);
    }

    static void Glow(string path, Color c)
    {
        var b = new Bitmap(16, 16, PixelFormat.Format32bppArgb);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
            {
                double d = Math.Abs(x - 7.5) / 7.5;
                int a = (int)(150 - 90 * d);
                b.SetPixel(x, y, Color.FromArgb(a, c.R, c.G, c.B));
            }
        Save(b, path);
    }

    static int Cl(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }
    static int Mix(int a, int b, double t) { return Cl((int)(a + (b - a) * t)); }

    static string Faces(string tex)
    {
        var sb = new StringBuilder("{");
        string[] f = { "north", "south", "east", "west", "up", "down" };
        for (int i = 0; i < f.Length; i++)
        {
            if (i > 0) sb.Append(',');
            sb.Append('"').Append(f[i]).Append("\":{\"uv\":[0,0,16,16],\"texture\":\"#").Append(tex).Append("\"}");
        }
        return sb.Append('}').ToString();
    }

    static string F(double[] a)
    {
        var inv = System.Globalization.CultureInfo.InvariantCulture;
        var s = new string[a.Length];
        for (int i = 0; i < a.Length; i++) s[i] = a[i].ToString("0.###", inv);
        return "[" + string.Join(",", s) + "]";
    }

    static string El(double[] from, double[] to, string tex, bool glow)
    {
        return "{\"from\":" + F(from) + ",\"to\":" + F(to)
            + ",\"rotation\":{\"angle\":-45,\"axis\":\"z\",\"origin\":[8,8,8]}"
            + (glow ? ",\"light_emission\":15" : "")
            + ",\"faces\":" + Faces(tex) + "}";
    }

    static string Model(string color, int stage)
    {
        var els = new System.Collections.Generic.List<string>();
        // hilt centred where a vanilla sword's grip is, blade up to the model's limit
        els.Add(El(new double[] { 7, -3, 7 }, new double[] { 9, 5, 9 }, "hilt", false));                 // grip
        els.Add(El(new double[] { 6.6, 4, 6.6 }, new double[] { 9.4, 5.4, 9.4 }, "hilt", false));         // emitter
        els.Add(El(new double[] { 7.3, -3.6, 7.3 }, new double[] { 8.7, -3, 8.7 }, "hilt", false));       // pommel
        if (stage > 0)
        {
            double top = 5.4 + 26.4 * stage / 3.0;
            els.Add(El(new double[] { 7.55, 5.4, 7.55 }, new double[] { 8.45, top, 8.45 }, "core", true));
            els.Add(El(new double[] { 7.1, 5.4, 7.1 }, new double[] { 8.9, Math.Min(32, top + 0.2), 8.9 }, "glow", true));
        }
        return "{\"textures\":{\"hilt\":\"neyrocraft:item/saber_hilt\",\"core\":\"neyrocraft:item/saber_core_" + color
            + "\",\"glow\":\"neyrocraft:item/saber_glow_" + color + "\",\"particle\":\"neyrocraft:item/saber_hilt\"},"
            + "\"elements\":[" + string.Join(",", els) + "],"
            + "\"display\":{"
            + "\"thirdperson_righthand\":{\"rotation\":[0,-90,55],\"translation\":[0,4,0.5],\"scale\":[0.7,0.7,0.7]},"
            + "\"thirdperson_lefthand\":{\"rotation\":[0,90,-55],\"translation\":[0,4,0.5],\"scale\":[0.7,0.7,0.7]},"
            + "\"firstperson_righthand\":{\"rotation\":[0,-90,25],\"translation\":[1.13,3.2,1.13],\"scale\":[0.56,0.56,0.56]},"
            + "\"firstperson_lefthand\":{\"rotation\":[0,90,-25],\"translation\":[1.13,3.2,1.13],\"scale\":[0.56,0.56,0.56]},"
            + "\"gui\":{\"rotation\":[0,0,0],\"translation\":[-2.6,-2.6,0],\"scale\":[0.6,0.6,0.6]},"
            + "\"ground\":{\"rotation\":[0,0,0],\"translation\":[0,2,0],\"scale\":[0.4,0.4,0.4]},"
            + "\"fixed\":{\"rotation\":[0,180,0],\"scale\":[0.55,0.55,0.55]},"
            + "\"head\":{\"rotation\":[0,180,0],\"translation\":[0,13,7],\"scale\":[0.8,0.8,0.8]}"
            + "}}";
    }
}
