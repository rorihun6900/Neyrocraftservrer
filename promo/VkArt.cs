using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Drawing.Text;

// VK community art for NeyroCraft in a Minecraft pixel style: the group photo and a space cover.
public static class VkArt
{
    static readonly Color ORANGE = Color.FromArgb(255, 140, 0), BLUE = Color.FromArgb(79, 195, 255);

    // ---------------------------------------------------------------- pixel font for the logo
    static readonly Dictionary<char, string[]> G = new Dictionary<char, string[]>
    {
        {'N', new[]{"X...X","XX..X","X.X.X","X..XX","X...X","X...X","X...X","....."}},
        {'e', new[]{".....",".....",".XXX.","X...X","XXXXX","X....",".XXXX","....."}},
        {'y', new[]{".....",".....","X...X","X...X","X...X",".XXXX","....X","XXXX."}},
        {'r', new[]{".....",".....","X.XX.","XX..X","X....","X....","X....","....."}},
        {'o', new[]{".....",".....",".XXX.","X...X","X...X","X...X",".XXX.","....."}},
        {'C', new[]{".XXX.","X...X","X....","X....","X....","X...X",".XXX.","....."}},
        {'a', new[]{".....",".....",".XXX.","....X",".XXXX","X...X",".XXXX","....."}},
        {'f', new[]{"..XX",".X..","XXXX",".X..",".X..",".X..",".X..","...."}},
        {'t', new[]{".X.",".X.","XXX",".X.",".X.",".X.","..X","..."}},
    };

    static int Cl(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }
    static Color Mul(Color c, double k) { return Color.FromArgb(c.A, Cl((int)(c.R * k)), Cl((int)(c.G * k)), Cl((int)(c.B * k))); }
    static Color Mix(Color a, Color b, double k) { return Color.FromArgb(255, Cl((int)(a.R + (b.R - a.R) * k)), Cl((int)(a.G + (b.G - a.G) * k)), Cl((int)(a.B + (b.B - a.B) * k))); }

    static int LogoWidth(string s)
    {
        int w = 0;
        foreach (char ch in s) w += G[ch][0].Length + 1;
        return w - 1;
    }

    /** Minecraft-logo style: every font pixel is a little block with a lit top-left edge and a deep shadow. */
    static void Logo(Bitmap b, string s, int x0, int y0, int px, Func<int, Color> colorOf)
    {
        int depth = Math.Max(2, px / 2);
        for (int pass = 0; pass < 2; pass++)
        {
            int x = x0, idx = 0;
            foreach (char ch in s)
            {
                string[] g = G[ch];
                Color c = colorOf(idx);
                for (int r = 0; r < g.Length; r++)
                    for (int q = 0; q < g[r].Length; q++)
                    {
                        if (g[r][q] != 'X') continue;
                        int bx = x + q * px, by = y0 + r * px;
                        if (pass == 0)
                        {
                            // extrusion
                            for (int d = 1; d <= depth; d++) Fill(b, bx + d, by + d, px, px, Mul(c, 0.32));
                        }
                        else
                        {
                            double shade = 1.0 - r * 0.045;          // a little darker towards the bottom
                            Color face = Mul(c, shade);
                            Fill(b, bx, by, px, px, face);
                            Fill(b, bx, by, px, 1, Mix(face, Color.White, 0.45));
                            Fill(b, bx, by, 1, px, Mix(face, Color.White, 0.25));
                            Fill(b, bx, by + px - 1, px, 1, Mul(face, 0.75));
                            Fill(b, bx + px - 1, by, 1, px, Mul(face, 0.8));
                        }
                    }
                x += (g[0].Length + 1) * px;
                idx++;
            }
        }
    }

    static void Fill(Bitmap b, int x, int y, int w, int h, Color c)
    {
        for (int yy = Math.Max(0, y); yy < Math.Min(b.Height, y + h); yy++)
            for (int xx = Math.Max(0, x); xx < Math.Min(b.Width, x + w); xx++) b.SetPixel(xx, yy, c);
    }

    static Bitmap Up(Bitmap small, int k)
    {
        var big = new Bitmap(small.Width * k, small.Height * k, PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(big))
        {
            g.InterpolationMode = InterpolationMode.NearestNeighbor;
            g.PixelOffsetMode = PixelOffsetMode.Half;
            g.DrawImage(small, new Rectangle(0, 0, big.Width, big.Height));
        }
        return big;
    }

    // ---------------------------------------------------------------- space background (pixel)

    static void Space(Bitmap b, int seed)
    {
        var r = new Random(seed);
        Color top = Color.FromArgb(6, 4, 22), mid = Color.FromArgb(24, 10, 52), low = Color.FromArgb(10, 22, 58);
        for (int y = 0; y < b.Height; y++)
            for (int x = 0; x < b.Width; x++)
            {
                double k = y / (double)b.Height;
                Color c = k < 0.5 ? Mix(top, mid, k * 2) : Mix(mid, low, (k - 0.5) * 2);
                // nebula: two soft clouds, dithered in steps like Minecraft skies
                double n1 = Math.Exp(-(Math.Pow((x - b.Width * 0.28) / (b.Width * 0.22), 2) + Math.Pow((y - b.Height * 0.45) / (b.Height * 0.35), 2)));
                double n2 = Math.Exp(-(Math.Pow((x - b.Width * 0.74) / (b.Width * 0.2), 2) + Math.Pow((y - b.Height * 0.55) / (b.Height * 0.4), 2)));
                double dither = ((x + y) % 2 == 0) ? 0.04 : 0;
                c = Mix(c, Color.FromArgb(120, 40, 170), Math.Floor((n1 * 0.55 + dither) * 6) / 6);
                c = Mix(c, Color.FromArgb(30, 110, 190), Math.Floor((n2 * 0.5 + dither) * 6) / 6);
                b.SetPixel(x, y, c);
            }
        int stars = b.Width * b.Height / 60;
        for (int i = 0; i < stars; i++)
        {
            int x = r.Next(b.Width), y = r.Next(b.Height);
            int v = r.Next(120, 256);
            Color s = r.Next(6) == 0 ? Color.FromArgb(255, 220, 160) : r.Next(5) == 0 ? Color.FromArgb(170, 220, 255) : Color.FromArgb(v, v, v);
            b.SetPixel(x, y, s);
            if (r.Next(14) == 0)
            {   // bright star: a little plus
                Fill(b, x - 1, y, 3, 1, Mul(s, 0.7)); Fill(b, x, y - 1, 1, 3, Mul(s, 0.7)); b.SetPixel(x, y, Color.White);
            }
        }
    }

    static void Planet(Bitmap b, int cx, int cy, int rad, Color light, Color dark, bool ring)
    {
        for (int y = -rad; y <= rad; y++)
            for (int x = -rad; x <= rad; x++)
            {
                double d = Math.Sqrt(x * x + y * y);
                if (d > rad) continue;
                double lit = (-(x + y) / (double)rad + 1.4) / 2.4;            // light from the top-left
                double band = Math.Sin((y + cy) * 0.9 + x * 0.1) * 0.08;
                double k = Math.Floor(Math.Max(0, Math.Min(1, lit + band)) * 5) / 5;
                Color c = Mix(dark, light, k);
                if (d > rad - 1) c = Mul(c, 0.7);
                b.SetPixel(cx + x, cy + y, c);
            }
        if (!ring) return;
        // tilted elliptic ring; its far half hides behind the planet
        double a = rad * 1.9, bb = rad * 0.42, tilt = -0.28;
        int steps = (int)(a * 16);
        for (int i = 0; i < steps; i++)
        {
            double ang = i * 2 * Math.PI / steps;
            double ex = Math.Cos(ang) * a, ey = Math.Sin(ang) * bb;
            double rx = ex * Math.Cos(tilt) - ey * Math.Sin(tilt), ry = ex * Math.Sin(tilt) + ey * Math.Cos(tilt);
            bool far = Math.Sin(ang) < 0;
            if (far && rx * rx + ry * ry < rad * rad) continue;
            Color rc = Mix(Color.FromArgb(255, 220, 170), Color.FromArgb(190, 110, 60), (Math.Cos(ang) + 1) / 2);
            int px = cx + (int)Math.Round(rx), py = cy + (int)Math.Round(ry);
            if (px >= 0 && py >= 0 && px < b.Width && py < b.Height) b.SetPixel(px, py, far ? Mul(rc, 0.7) : rc);
        }
    }

    // ---------------------------------------------------------------- group photo

    public static void Avatar(string path)
    {
        int S = 50;
        var b = new Bitmap(S, S, PixelFormat.Format32bppArgb);
        Space(b, 11);
        Planet(b, 41, 10, 4, Color.FromArgb(255, 170, 70), Color.FromArgb(110, 30, 10), true);
        Planet(b, 8, 42, 3, Color.FromArgb(140, 220, 255), Color.FromArgb(20, 50, 110), false);
        // a Minecraft block seen straight on: dark space-stone with bevelled edges and a glowing frame
        int x0 = 10, y0 = 10, n = 30;
        var r = new Random(4);
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++)
            {
                int v = r.Next(4);
                Color c = Mix(Color.FromArgb(26, 28, 48), Color.FromArgb(46, 50, 80), v / 4.0);
                if (x == 0 || y == 0) c = Color.FromArgb(90, 96, 140);                       // lit edges
                else if (x == n - 1 || y == n - 1) c = Color.FromArgb(12, 12, 24);           // shadow edges
                else if (x == 2 || y == 2 || x == n - 3 || y == n - 3) c = (x + y) % 2 == 0 ? BLUE : Color.FromArgb(40, 140, 200);   // glowing frame
                b.SetPixel(x0 + x, y0 + y, c);
            }
        // corner rivets
        foreach (var p in new[] { new Point(4, 4), new Point(n - 5, 4), new Point(4, n - 5), new Point(n - 5, n - 5) })
            b.SetPixel(x0 + p.X, y0 + p.Y, ORANGE);
        // the big orange N in the same blocky style as the cover logo
        Logo(b, "N", x0 + (n - 15) / 2, y0 + (n - 21) / 2 - 1, 3, i => ORANGE);
        var big = Up(b, 16);
        big.Save(path, ImageFormat.Png);
        big.Dispose(); b.Dispose();
    }

    // ---------------------------------------------------------------- cover

    static void Text(Graphics g, string s, Font f, Color c, float x, float y, bool centre, float width)
    {
        var fmt = new StringFormat { Alignment = centre ? StringAlignment.Center : StringAlignment.Near };
        var rect = new RectangleF(x, y, width, f.Height * 2);
        g.DrawString(s, f, new SolidBrush(Mul(c, 0.25)), new RectangleF(x + 2, y + 2, width, f.Height * 2), fmt);   // Minecraft-style shadow
        g.DrawString(s, f, new SolidBrush(c), rect, fmt);
    }

    public static void Cover(string path)
    {
        // pixel layer at quarter size
        int W = 480, H = 192;
        var b = new Bitmap(W, H, PixelFormat.Format32bppArgb);
        Space(b, 5);
        Planet(b, 418, 92, 22, Color.FromArgb(255, 170, 70), Color.FromArgb(110, 30, 10), true);      // Terrax
        Planet(b, 52, 150, 14, Color.FromArgb(200, 150, 255), Color.FromArgb(50, 20, 100), false);    // Orion nebula world
        Planet(b, 112, 80, 5, Color.FromArgb(150, 230, 255), Color.FromArgb(20, 60, 120), false);
        // logo
        string logo = "NeyroCraft";
        int px = 4, lw = LogoWidth(logo) * px;
        Logo(b, logo, (W - lw) / 2, 69, px, i => i < 5 ? ORANGE : BLUE);
        var big = Up(b, 4);

        using (var g = Graphics.FromImage(big))
        {
            g.TextRenderingHint = TextRenderingHint.SingleBitPerPixelGridFit;      // hard pixel edges like the game font
            g.SmoothingMode = SmoothingMode.None;
            var sub = new Font("Segoe UI Black", 34f, FontStyle.Bold, GraphicsUnit.Pixel);
            var head = new Font("Segoe UI Black", 40f, FontStyle.Bold, GraphicsUnit.Pixel);
            var item = new Font("Segoe UI", 30f, FontStyle.Bold, GraphicsUnit.Pixel);
            var foot = new Font("Consolas", 28f, FontStyle.Bold, GraphicsUnit.Pixel);
            Text(g, "сервер на космическом корабле", sub, Color.FromArgb(220, 225, 245), 0, 402, true, 1920);

            // panel for the list
            using (var br = new SolidBrush(Color.FromArgb(150, 8, 6, 24))) g.FillRectangle(br, 380, 450, 1160, 250);
            using (var pen = new Pen(Color.FromArgb(200, 79, 195, 255), 4)) g.DrawRectangle(pen, 380, 450, 1160, 250);
            using (var pen = new Pen(Color.FromArgb(200, 255, 140, 0), 4)) { g.DrawLine(pen, 380, 450, 520, 450); g.DrawLine(pen, 1400, 700, 1540, 700); }
            Text(g, "★  НОВЫЕ МИНИ-ИГРЫ  ★", head, Color.FromArgb(255, 213, 74), 0, 452, true, 1920);

            var games = new[]
            {
                Tuple.Create("◆ Гонки на спидерах", Color.FromArgb(255, 160, 64)),
                Tuple.Create("◆ Световые мечи", Color.FromArgb(127, 232, 255)),
                Tuple.Create("◆ Лазертаг", Color.FromArgb(255, 90, 90)),
                Tuple.Create("◆ Minecraft 1.0", Color.FromArgb(85, 255, 85)),
                Tuple.Create("◆ SkyBlock", Color.FromArgb(85, 255, 255)),
                Tuple.Create("◆ Выживание", Color.FromArgb(255, 224, 102)),
                Tuple.Create("◆ StarPass — 30 уровней", Color.FromArgb(180, 140, 255)),
                Tuple.Create("◆ Станция «Орион»", Color.FromArgb(224, 123, 255)),
            };
            for (int i = 0; i < games.Length; i++)
            {
                int col = i % 2, row = i / 2;
                Text(g, games[i].Item1, item, games[i].Item2, 440 + col * 560, 504 + row * 46, false, 560);
            }
            Text(g, "neyrocraft.duckdns.org  ·  версия 26.3  ·  пиратка ✓", foot, Color.FromArgb(255, 255, 255), 0, 708, true, 1920);
        }
        big.Save(path, ImageFormat.Png);
        big.Dispose(); b.Dispose();
    }
}
