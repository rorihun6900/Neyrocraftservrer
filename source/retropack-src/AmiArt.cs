using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Text;

// Ami — an original drawing of a young scientist, turned into a hologram (cyan light, scanlines, glowing edge).
public static class AmiArt
{
    const int W = 96, H = 192;

    public static void Generate(string pngOut, string pixelsOut, string previewOut)
    {
        using (var src = new Bitmap(W, H, PixelFormat.Format32bppArgb))
        {
            using (var g = Graphics.FromImage(src))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.Clear(Color.Transparent);
                Draw(g);
            }
            using (var holo = Hologram(src))
            {
                holo.Save(pngOut, ImageFormat.Png);
                File.WriteAllText(pixelsOut, Pixels(holo, 24, 40), new UTF8Encoding(false));
                using (var prev = new Bitmap(W * 3, H * 3))
                using (var pg = Graphics.FromImage(prev))
                {
                    pg.Clear(Color.FromArgb(255, 18, 22, 34));
                    pg.InterpolationMode = InterpolationMode.NearestNeighbor;
                    pg.PixelOffsetMode = PixelOffsetMode.Half;
                    pg.DrawImage(holo, 0, 0, W * 3, H * 3);
                    prev.Save(previewOut, ImageFormat.Png);
                }
            }
        }
    }

    static void Fill(Graphics g, Color c, params PointF[] pts) { using (var b = new SolidBrush(c)) g.FillPolygon(b, pts); }
    static PointF P(float x, float y) { return new PointF(x, y); }

    static void Draw(Graphics g)
    {
        // anime proportions: big head, huge eyes, long hair with an ahoge
        Color hair = Color.FromArgb(255, 70, 52, 110), hairDk = Color.FromArgb(255, 45, 32, 78), hairHi = Color.FromArgb(255, 150, 130, 210);
        Color skin = Color.FromArgb(255, 252, 226, 210), skinSh = Color.FromArgb(255, 230, 196, 182);
        Color coat = Color.FromArgb(255, 244, 246, 252), coatSh = Color.FromArgb(255, 196, 204, 222);
        Color dress = Color.FromArgb(255, 70, 100, 190), dark = Color.FromArgb(255, 36, 30, 60);
        Color iris = Color.FromArgb(255, 40, 120, 220), irisDk = Color.FromArgb(255, 20, 50, 130);

        // long hair behind the body, pointed ends
        Fill(g, hairDk, P(24, 40), P(30, 18), P(48, 10), P(66, 18), P(72, 40), P(74, 80), P(72, 112), P(66, 104), P(62, 118), P(56, 106),
             P(40, 106), P(34, 118), P(30, 104), P(24, 112), P(22, 80));
        // legs and boots
        Fill(g, skin, P(39, 146), P(46, 146), P(45, 172), P(40, 172));
        Fill(g, skin, P(50, 146), P(57, 146), P(56, 172), P(51, 172));
        Fill(g, dark, P(38, 170), P(47, 170), P(47, 186), P(36, 186));
        Fill(g, dark, P(49, 170), P(58, 170), P(60, 186), P(49, 186));
        // dress under the coat
        Fill(g, dress, P(38, 76), P(58, 76), P(62, 140), P(34, 140));
        Fill(g, Color.FromArgb(255, 230, 240, 255), P(34, 136), P(62, 136), P(64, 142), P(32, 142));
        // lab coat, open, with a flared hem
        Fill(g, coat, P(30, 74), P(40, 74), P(38, 150), P(20, 150), P(24, 100));
        Fill(g, coat, P(56, 74), P(66, 74), P(72, 100), P(76, 150), P(58, 150));
        Fill(g, coatSh, P(36, 78), P(40, 78), P(38, 150), P(34, 150));
        Fill(g, coatSh, P(56, 78), P(60, 78), P(62, 150), P(58, 150));
        // sleeves and hands holding a tablet in front of her
        Fill(g, coat, P(26, 76), P(34, 78), P(40, 100), P(32, 104), P(24, 96));
        Fill(g, coat, P(70, 76), P(62, 78), P(56, 100), P(64, 104), P(72, 96));
        Fill(g, Color.FromArgb(255, 30, 40, 64), P(34, 92), P(62, 92), P(62, 108), P(34, 108));
        using (var pen = new Pen(Color.FromArgb(255, 140, 240, 255), 1.4f)) g.DrawLines(pen, new[] { P(37, 104), P(42, 98), P(47, 101), P(53, 95), P(59, 97) });
        Fill(g, skin, P(32, 100), P(38, 98), P(38, 104), P(33, 106));
        Fill(g, skin, P(64, 100), P(58, 98), P(58, 104), P(63, 106));
        // neck and face (pointed anime chin)
        Fill(g, skinSh, P(44, 62), P(52, 62), P(52, 76), P(44, 76));
        using (var b = new SolidBrush(skin)) g.FillEllipse(b, 28, 20, 40, 40);
        Fill(g, skin, P(30, 46), P(66, 46), P(56, 64), P(48, 70), P(40, 64));
        // big eyes
        foreach (float ex in new[] { 33f, 52f })
        {
            using (var b = new SolidBrush(Color.White)) g.FillEllipse(b, ex, 39, 11, 13);
            using (var b = new LinearGradientBrush(new RectangleF(ex + 1, 39, 9, 13), iris, irisDk, 90f)) g.FillEllipse(b, ex + 1, 39.5f, 9, 12.5f);
            using (var b = new SolidBrush(Color.FromArgb(255, 10, 20, 60))) g.FillEllipse(b, ex + 3.5f, 43, 4, 6);
            using (var b = new SolidBrush(Color.White)) { g.FillEllipse(b, ex + 2, 41, 3.6f, 3.6f); g.FillEllipse(b, ex + 6.5f, 47, 1.8f, 1.8f); }
            Fill(g, dark, P(ex - 1.5f, 40.5f), P(ex + 2, 37.5f), P(ex + 10, 37.5f), P(ex + 12.5f, 39.5f), P(ex + 11, 41), P(ex + 1, 40), P(ex - 0.5f, 42));
            using (var pen = new Pen(dark, 0.9f)) g.DrawLine(pen, ex + 3, 52.5f, ex + 8, 52.5f);
            using (var pen = new Pen(hairDk, 1.1f)) g.DrawArc(pen, ex + 0.5f, 31, 10, 5, 200, 140);
        }
        // blush, nose, little smile
        using (var b = new SolidBrush(Color.FromArgb(150, 255, 140, 160))) { g.FillEllipse(b, 31, 53, 8, 3.5f); g.FillEllipse(b, 57, 53, 8, 3.5f); }
        using (var b = new SolidBrush(skinSh)) g.FillEllipse(b, 47.5f, 55, 1.5f, 1.5f);
        using (var pen = new Pen(Color.FromArgb(255, 190, 80, 100), 1.1f)) g.DrawArc(pen, 45, 58, 6, 4, 10, 160);
        // fringe: spiky bangs, long side locks, shiny highlight band and an ahoge
        Fill(g, hair, P(27, 40), P(28, 24), P(40, 14), P(56, 14), P(68, 24), P(69, 40), P(64, 30), P(61, 40), P(57, 27), P(53, 38),
             P(49, 25), P(45, 38), P(41, 27), P(37, 40), P(33, 30));
        Fill(g, hair, P(27, 34), P(32, 38), P(32, 74), P(29, 86), P(26, 70));
        Fill(g, hair, P(69, 34), P(64, 38), P(64, 74), P(67, 86), P(70, 70));
        using (var pen = new Pen(hairHi, 1.6f)) { g.DrawArc(pen, 32, 17, 32, 10, 200, 140); }
        using (var pen = new Pen(hair, 2.2f)) g.DrawBezier(pen, P(48, 14), P(50, 4), P(58, 2), P(56, 8));
        // star hair clip and a small earpiece
        Fill(g, Color.FromArgb(255, 255, 190, 60), P(62, 24), P(63.5f, 27), P(67, 27.5f), P(64.3f, 29.5f), P(65.3f, 33), P(62, 31), P(58.7f, 33), P(59.7f, 29.5f), P(57, 27.5f), P(60.5f, 27));
        using (var b = new SolidBrush(Color.FromArgb(255, 120, 230, 255))) g.FillEllipse(b, 25, 44, 4, 6);
    }
    static Bitmap Hologram(Bitmap src)
    {
        var o = new Bitmap(W, H, PixelFormat.Format32bppArgb);
        var r = new Random(5);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
            {
                Color c = src.GetPixel(x, y);
                if (c.A < 40) { o.SetPixel(x, y, Color.Transparent); continue; }
                double lum = (0.3 * c.R + 0.59 * c.G + 0.11 * c.B) / 255.0;
                bool edge = false;
                for (int dx = -1; dx <= 1 && !edge; dx++)
                    for (int dy = -1; dy <= 1 && !edge; dy++)
                    {
                        int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= W || yy >= H || src.GetPixel(xx, yy).A < 40) edge = true;
                    }
                int rr = (int)(20 + 150 * lum), gg = (int)(120 + 130 * lum), bb = (int)(170 + 85 * lum);
                int a = (int)(165 + 70 * lum) + r.Next(-12, 12);
                if (y % 4 == 0) a = (int)(a * 0.74);                  // scanlines
                if (edge) { rr = 190; gg = 255; bb = 255; a = 245; }  // glowing outline
                rr = (int)(rr * 0.75 + c.R * 0.25); gg = (int)(gg * 0.8 + c.G * 0.2); bb = (int)(bb * 0.8 + c.B * 0.2);   // a touch of her real colours
                o.SetPixel(x, y, Color.FromArgb(Math.Max(0, Math.Min(255, a)), Math.Min(255, rr), Math.Min(255, gg), Math.Min(255, bb)));
            }
        return o;
    }

    /** Downsampled colours for the no-resource-pack fallback (text pixels). */
    static string Pixels(Bitmap img, int cols, int rows)
    {
        var sb = new StringBuilder();
        for (int ry = 0; ry < rows; ry++)
        {
            for (int rx = 0; rx < cols; rx++)
            {
                int x0 = rx * W / cols, x1 = (rx + 1) * W / cols, y0 = ry * H / rows, y1 = (ry + 1) * H / rows;
                long sr = 0, sg = 0, sbb = 0, sa = 0, n = 0;
                for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) { var c = img.GetPixel(x, y); sr += c.R * c.A; sg += c.G * c.A; sbb += c.B * c.A; sa += c.A; n++; }
                if (rx > 0) sb.Append(' ');
                if (sa / n < 70) sb.Append("------");
                else sb.AppendFormat("{0:X2}{1:X2}{2:X2}", sr / sa, sg / sa, sbb / sa);
            }
            sb.Append('\n');
        }
        return sb.ToString();
    }
}
