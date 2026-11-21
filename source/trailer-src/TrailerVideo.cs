using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Drawing.Text;
using System.IO;
using System.Runtime.InteropServices;

// NeyroCraft trailer: 43 s of animated pixel art (480×270 scaled ×4 to 1920×1080, 30 fps), in the style
// of the VK cover — space intro, warp, the logo drop, one scene per mode, and the end card with the address.
public static class TrailerVideo
{
    const int W = 480, H = 270, K = 4, FPS = 30;
    public const double LEN = 43.0;
    static int[] px = new int[W * H];
    static Random rnd = new Random(3);

    static readonly int ORANGE = 0xFF8C00, BLUE = 0x4FC3FF;

    // ---------------------------------------------------------------- tiny raster helpers
    static int Rgb(int r, int g, int b) { return (255 << 24) | (Cl(r) << 16) | (Cl(g) << 8) | Cl(b); }
    static int Cl(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }
    static int R(int c) { return (c >> 16) & 255; }
    static int Gc(int c) { return (c >> 8) & 255; }
    static int B(int c) { return c & 255; }
    static int Mix(int a, int b, double k)
    {
        k = Math.Max(0, Math.Min(1, k));
        return Rgb((int)(R(a) + (R(b) - R(a)) * k), (int)(Gc(a) + (Gc(b) - Gc(a)) * k), (int)(B(a) + (B(b) - B(a)) * k));
    }
    static int Mul(int c, double k) { return Rgb((int)(R(c) * k), (int)(Gc(c) * k), (int)(B(c) * k)); }
    static void Set(int x, int y, int c) { if (x >= 0 && y >= 0 && x < W && y < H) px[y * W + x] = c; }
    static int Get(int x, int y) { return px[Math.Max(0, Math.Min(H - 1, y)) * W + Math.Max(0, Math.Min(W - 1, x))]; }
    static void Blend(int x, int y, int c, double a) { if (x >= 0 && y >= 0 && x < W && y < H) px[y * W + x] = Mix(px[y * W + x], c, a); }
    static void Rect(int x, int y, int w, int h, int c) { for (int yy = y; yy < y + h; yy++) for (int xx = x; xx < x + w; xx++) Set(xx, yy, c); }
    static void RectA(int x, int y, int w, int h, int c, double a) { for (int yy = y; yy < y + h; yy++) for (int xx = x; xx < x + w; xx++) Blend(xx, yy, c, a); }
    static void Line(double x0, double y0, double x1, double y1, int c, double a)
    {
        int n = (int)Math.Max(1, Math.Max(Math.Abs(x1 - x0), Math.Abs(y1 - y0)));
        for (int i = 0; i <= n; i++) { double t = i / (double)n; Blend((int)Math.Round(x0 + (x1 - x0) * t), (int)Math.Round(y0 + (y1 - y0) * t), c, a); }
    }
    static void Glow(double x0, double y0, double x1, double y1, int c)
    {
        Line(x0, y0 - 1, x1, y1 - 1, c, 0.35); Line(x0, y0 + 1, x1, y1 + 1, c, 0.35);
        Line(x0 - 1, y0, x1 - 1, y1, c, 0.35); Line(x0 + 1, y0, x1 + 1, y1, c, 0.35);
        Line(x0, y0, x1, y1, Mix(c, 0xFFFFFF, 0.6), 1);
    }
    static void Disc(double cx, double cy, double r, int c, double a)
    {
        for (int y = (int)(cy - r); y <= cy + r; y++) for (int x = (int)(cx - r); x <= cx + r; x++)
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) Blend(x, y, c, a);
    }

    // ---------------------------------------------------------------- shared backgrounds
    struct Star { public double x, y, z; public int c; }
    static List<Star> stars = new List<Star>();
    static void InitStars()
    {
        for (int i = 0; i < 520; i++)
        {
            int v = rnd.Next(130, 256);
            int c = rnd.Next(6) == 0 ? Rgb(255, 220, 160) : rnd.Next(5) == 0 ? Rgb(170, 220, 255) : Rgb(v, v, v);
            stars.Add(new Star { x = rnd.NextDouble() * W, y = rnd.NextDouble() * H, z = 0.2 + rnd.NextDouble() * 0.8, c = c });
        }
    }

    static void Space(double t, double drift, double bright)
    {
        int top = Rgb(6, 4, 22), mid = Rgb(24, 10, 52), low = Rgb(10, 22, 58);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
            {
                double k = y / (double)H;
                int c = k < 0.5 ? Mix(top, mid, k * 2) : Mix(mid, low, (k - 0.5) * 2);
                double n1 = Math.Exp(-(Math.Pow((x - W * 0.3 + drift * 0.2) / (W * 0.22), 2) + Math.Pow((y - H * 0.45) / (H * 0.35), 2)));
                double n2 = Math.Exp(-(Math.Pow((x - W * 0.72 + drift * 0.2) / (W * 0.2), 2) + Math.Pow((y - H * 0.55) / (H * 0.4), 2)));
                double dither = ((x + y) % 2 == 0) ? 0.04 : 0;
                c = Mix(c, Rgb(120, 40, 170), Math.Floor((n1 * 0.55 + dither) * 6) / 6);
                c = Mix(c, Rgb(30, 110, 190), Math.Floor((n2 * 0.5 + dither) * 6) / 6);
                px[y * W + x] = Mul(c, bright);
            }
        foreach (var s in stars)
        {
            double x = ((s.x - drift * s.z) % W + W) % W;
            double tw = 0.6 + 0.4 * Math.Sin(t * 3 + s.x);
            Blend((int)x, (int)s.y, s.c, bright * tw);
        }
    }

    static void Planet(int cx, int cy, int rad, int light, int dark, bool ring)
    {
        for (int y = -rad; y <= rad; y++)
            for (int x = -rad; x <= rad; x++)
            {
                double d = Math.Sqrt(x * x + y * y);
                if (d > rad) continue;
                double lit = (-(x + y) / (double)rad + 1.4) / 2.4;
                double band = Math.Sin((y + cy) * 0.9 + x * 0.1) * 0.08;
                double k = Math.Floor(Math.Max(0, Math.Min(1, lit + band)) * 5) / 5;
                int c = Mix(dark, light, k);
                if (d > rad - 1) c = Mul(c, 0.7);
                Set(cx + x, cy + y, c);
            }
        if (!ring) return;
        double a = rad * 1.9, bb = rad * 0.42, tilt = -0.28;
        int steps = (int)(a * 16);
        for (int i = 0; i < steps; i++)
        {
            double ang = i * 2 * Math.PI / steps;
            double ex = Math.Cos(ang) * a, ey = Math.Sin(ang) * bb;
            double rx = ex * Math.Cos(tilt) - ey * Math.Sin(tilt), ry = ex * Math.Sin(tilt) + ey * Math.Cos(tilt);
            bool far = Math.Sin(ang) < 0;
            if (far && rx * rx + ry * ry < rad * rad) continue;
            int rc = Mix(Rgb(255, 220, 170), Rgb(190, 110, 60), (Math.Cos(ang) + 1) / 2);
            Set(cx + (int)Math.Round(rx), cy + (int)Math.Round(ry), far ? Mul(rc, 0.7) : rc);
        }
    }

    // ---------------------------------------------------------------- logo font
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
    static int LogoWidth(string s, int p) { int w = 0; foreach (char ch in s) w += G[ch][0].Length + 1; return (w - 1) * p; }

    /** Blocky logo; each letter drops in from above (drop = 0..1 per letter), a shine sweeps across. */
    static void Logo(int x0, int y0, int p, double appear, double shine)
    {
        string s = "NeyroCraft";
        int x = x0;
        for (int li = 0; li < s.Length; li++)
        {
            string[] g = G[s[li]];
            double k = Math.Max(0, Math.Min(1, appear * 1.6 - li * 0.06));
            if (k <= 0) { x += (g[0].Length + 1) * p; continue; }
            double e = 1 - Math.Pow(1 - k, 3);
            int dy = (int)Math.Round(-(1 - e) * 60 + Math.Sin(k * Math.PI) * -3);
            int col = li < 5 ? ORANGE : BLUE;
            for (int pass = 0; pass < 2; pass++)
                for (int r = 0; r < g.Length; r++)
                    for (int q = 0; q < g[r].Length; q++)
                    {
                        if (g[r][q] != 'X') continue;
                        int bx = x + q * p, by = y0 + r * p + dy;
                        if (pass == 0) { for (int d = 1; d <= p / 2 + 1; d++) Rect(bx + d, by + d, p, p, Mul(col, 0.32)); continue; }
                        int face = Mul(col, 1.0 - r * 0.045);
                        double sh = Math.Exp(-Math.Pow((bx - (x0 - 40 + shine * (LogoWidth(s, p) + 80))) / 10.0, 2));
                        face = Mix(face, 0xFFFFFF, sh * 0.7);
                        Rect(bx, by, p, p, face);
                        Rect(bx, by, p, 1, Mix(face, 0xFFFFFF, 0.45));
                        Rect(bx, by, 1, p, Mix(face, 0xFFFFFF, 0.25));
                        Rect(bx, by + p - 1, p, 1, Mul(face, 0.75));
                    }
            x += (g[0].Length + 1) * p;
        }
    }

    // ---------------------------------------------------------------- blocky people
    static void Person(int x, int y, int s, int skin, int shirt, int pants, bool faceRight, double walk)
    {
        // y = feet. body parts in 'pixels' of size s: legs 3, torso 3, head 3 (side view, 2 wide body)
        int lw = (int)Math.Round(Math.Sin(walk) * s);
        Rect(x, y - 3 * s, s, 3 * s, pants);
        Rect(x + s, y - 3 * s, s, 3 * s, Mul(pants, 0.8));
        Rect(x, y - 6 * s, 2 * s, 3 * s, shirt);
        Rect(x, y - 9 * s, 2 * s, 3 * s, skin);
        Rect(x + (faceRight ? s + s / 2 : 0), y - 8 * s, Math.Max(1, s / 2), Math.Max(1, s / 2), Rgb(30, 30, 50));      // eye
        Rect(x, y - 9 * s, 2 * s, Math.Max(1, s / 2), Rgb(60, 40, 30));                                                    // hair
    }

    // ---------------------------------------------------------------- scenes
    static void Stars(double u)
    {
        double fade = Math.Min(1, u / 2.0);
        Space(u, u * 6, fade);
        Planet(400, 70, 18, Rgb(255, 170, 70), Rgb(110, 30, 10), true);
        RectA(0, 0, W, H, 0, 1 - fade);
    }

    static void Warp(double u)
    {
        // u 0..4
        double sp = 0.3 + u * u * 1.4;
        Space(u + 4, 24 + u * 10, 0.6);
        int cx = W / 2, cy = H / 2;
        var r = new Random(7);
        for (int i = 0; i < 260; i++)
        {
            double ang = r.NextDouble() * Math.PI * 2;
            double d0 = (r.NextDouble() * 300 + u * sp * 60) % 300;
            double d1 = d0 + 2 + sp * 6 * (d0 / 300);
            int c = r.Next(3) == 0 ? BLUE : r.Next(3) == 0 ? ORANGE : 0xFFFFFF;
            Line(cx + Math.Cos(ang) * d0, cy + Math.Sin(ang) * d0 * 0.6, cx + Math.Cos(ang) * d1, cy + Math.Sin(ang) * d1 * 0.6, c, 0.9);
        }
        // rings flying out of the centre
        for (int k = 0; k < 4; k++)
        {
            double rr = ((u * 60 + k * 45) % 180);
            int c = k % 2 == 0 ? ORANGE : BLUE;
            for (int i = 0; i < 120; i++) { double a = i * Math.PI / 60; Blend(cx + (int)(Math.Cos(a) * rr), cy + (int)(Math.Sin(a) * rr * 0.6), c, 0.8 * (1 - rr / 180)); }
        }
        if (u > 3.6) RectA(0, 0, W, H, 0xFFFFFF, (u - 3.6) / 0.4);
    }

    static void LogoScene(double u)
    {
        Space(u + 8, 40 + u * 4, 1);
        Planet(410, 70, 20, Rgb(255, 170, 70), Rgb(110, 30, 10), true);
        Planet(60, 210, 13, Rgb(200, 150, 255), Rgb(50, 20, 100), false);
        int p = 5, lw = LogoWidth("NeyroCraft", p);
        Logo((W - lw) / 2, 92, p, Math.Min(1, u / 1.0), Math.Max(0, Math.Min(1, (u - 1.2) / 1.0)));
        if (u < 0.5) RectA(0, 0, W, H, 0xFFFFFF, 1 - u / 0.5);
    }

    static void Races(double u)
    {
        // desert planet: warm sky, two moons, parallax mesas, the track and two speeders
        for (int y = 0; y < H; y++)
        {
            int c = Mix(Rgb(255, 150, 90), Rgb(120, 60, 120), 1 - y / 160.0);
            for (int x = 0; x < W; x++) px[y * W + x] = ((x + y) % 2 == 0 && y % 16 == 0) ? Mul(c, 0.97) : c;
        }
        Disc(380, 50, 18, Rgb(255, 235, 200), 1); Disc(386, 45, 18, Rgb(255, 150, 90), 0.0);
        Disc(320, 34, 7, Rgb(200, 230, 255), 0.9);
        double scroll = u * 220;
        MesaLayer(scroll * 0.2, 120, 40, Rgb(150, 70, 70), 11);
        MesaLayer(scroll * 0.45, 150, 34, Rgb(185, 85, 55), 23);
        // ground
        for (int y = 170; y < H; y++) for (int x = 0; x < W; x++) { int c = Mix(Rgb(205, 100, 60), Rgb(150, 60, 40), (y - 170) / 100.0); if (((x + (int)scroll) / 3 + y) % 7 == 0) c = Mul(c, 0.9); px[y * W + x] = c; }
        // track: dark road with moving red/white curbs
        for (int y = 196; y < 238; y++) for (int x = 0; x < W; x++) px[y * W + x] = Rgb(34, 32, 40);
        for (int x = 0; x < W; x++)
        {
            bool red = ((x + (int)scroll) / 12) % 2 == 0;
            Rect(x, 192, 1, 4, red ? Rgb(220, 40, 40) : Rgb(240, 240, 240));
            Rect(x, 238, 1, 4, red ? Rgb(240, 240, 240) : Rgb(220, 40, 40));
            if (((x + (int)scroll) / 20) % 2 == 0) Set(x, 217, Rgb(220, 220, 220));
        }
        // checkpoint arch passing by
        int ax = W - (int)((scroll * 1.0) % 700) + 100;
        Rect(ax, 140, 6, 56, Rgb(40, 40, 60)); Rect(ax + 60, 140, 6, 56, Rgb(40, 40, 60)); Rect(ax, 136, 66, 6, Rgb(80, 200, 255));
        Speeder(60 + (int)(u * 10), 226 + (int)(Math.Sin(u * 8 + 1) * 1.5), u, Rgb(230, 120, 60));
        Speeder(200 + (int)(Math.Sin(u * 1.3) * 18), 206 + (int)(Math.Sin(u * 9) * 1.5), u, Rgb(200, 205, 220));
    }

    static void MesaLayer(double scroll, int baseY, int height, int c, int seed)
    {
        for (int x = 0; x < W; x++)
        {
            double wx = x + scroll;
            double h = Math.Max(0, Math.Sin(wx * 0.013 + seed) * 0.6 + Math.Sin(wx * 0.031 + seed * 2) * 0.4);
            int top = baseY - (int)(Math.Floor(h * height / 6) * 6);
            for (int y = top; y < 175; y++) px[y * W + x] = ((y - top) % 6 == 0) ? Mul(c, 0.85) : c;
        }
    }

    static void Speeder(int x, int y, double u, int body)
    {
        Speeder2(x, y, u, body, 2);
    }

    static void SR(int ox, int oy, int s, int x, int y, int w, int h, int c) { Rect(ox + x * s, oy + y * s, w * s, h * s, c); }

    static void Speeder2(int ox, int oy, double u, int body, int s)
    {
        for (int i = 0; i < 70; i++) Blend(ox - 12 - i, oy + 4 + (int)(Math.Sin(i * 0.4 + u * 30) * 1.2), Mix(Rgb(255, 200, 80), Rgb(255, 80, 20), i / 70.0), 0.85 * (1 - i / 70.0));
        SR(ox, oy, s, -6, -2, 6, 6, Rgb(60, 62, 76));
        SR(ox, oy, s, 0, 0, 26, 5, body);
        SR(ox, oy, s, 4, -3, 9, 3, Rgb(40, 42, 52));
        SR(ox, oy, s, 26, 1, 12, 3, Mul(body, 0.8));
        SR(ox, oy, s, 36, -1, 10, 6, Mul(body, 0.9));
        SR(ox, oy, s, 45, 0, 2, 4, Rgb(110, 220, 255));
        SR(ox, oy, s, 0, 2, 26, 1, Rgb(210, 70, 40));
        SR(ox, oy, s, 6, -11, 5, 8, Rgb(40, 90, 160)); SR(ox, oy, s, 6, -16, 5, 5, Rgb(230, 190, 150)); SR(ox, oy, s, 6, -17, 5, 2, Rgb(30, 30, 40));
        for (int i = 0; i < 30 * s; i++) Blend(ox + i, oy + 7 * s, Rgb(255, 160, 60), 0.3);
    }

    static void SpeederOld(int x, int y, double u, int body)
    {
        // exhaust trail
        for (int i = 0; i < 40; i++) Blend(x - 6 - i, y + 2 + (int)(Math.Sin(i * 0.5 + u * 30) * 0.8), Mix(Rgb(255, 200, 80), Rgb(255, 80, 20), i / 40.0), 0.8 * (1 - i / 40.0));
        Rect(x - 6, y - 2, 6, 6, Rgb(60, 62, 76));                  // rear engine
        Rect(x, y, 26, 5, body);                                     // hull
        Rect(x + 4, y - 3, 9, 3, Rgb(40, 42, 52));                   // seat
        Rect(x + 26, y + 1, 12, 3, Mul(body, 0.8));                  // forks
        Rect(x + 36, y - 1, 10, 6, Mul(body, 0.9));                  // engine pods
        Rect(x + 45, y, 2, 4, Rgb(110, 220, 255));                   // intakes
        Rect(x, y + 2, 26, 1, Rgb(210, 70, 40));                     // racing stripe
        // rider
        Rect(x + 6, y - 11, 5, 8, Rgb(40, 90, 160)); Rect(x + 6, y - 16, 5, 5, Rgb(230, 190, 150)); Rect(x + 6, y - 17, 5, 2, Rgb(30, 30, 40));
        // hover glow under the bike
        for (int i = 0; i < 30; i++) Blend(x + i, y + 7, Rgb(255, 160, 60), 0.25);
    }

    static void Sabers(double u)
    {
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) px[y * W + x] = Mix(Rgb(10, 12, 26), Rgb(26, 20, 48), y / (double)H);
        // arena floor with perspective lines
        for (int y = 190; y < H; y++) for (int x = 0; x < W; x++) px[y * W + x] = Rgb(22, 22, 34);
        for (int i = -10; i <= 10; i++) Line(W / 2 + i * 8, 190, W / 2 + i * 60, H, Rgb(80, 200, 255), 0.25);
        for (int y = 194; y < H; y += (y - 186) / 4 + 2) Line(0, y, W, y, Rgb(80, 200, 255), 0.18);
        // pillars in the background
        for (int i = 0; i < 6; i++) { int x = 40 + i * 80; Rect(x, 70, 10, 120, Rgb(30, 30, 48)); Rect(x, 70, 10, 3, Rgb(127, 232, 255)); }
        int s = 6;
        int lx = 170 - (int)(Math.Sin(u * 2) * 6), rx = 290 + (int)(Math.Sin(u * 2) * 6);
        Person(lx, 200, s, Rgb(230, 190, 150), Rgb(40, 40, 60), Rgb(30, 30, 40), true, 0);
        Person(rx, 200, s, Rgb(200, 160, 130), Rgb(120, 30, 30), Rgb(40, 20, 20), false, 0);
        // blades swing and clash
        double a1 = -0.9 + Math.Sin(u * 5.0) * 0.9, a2 = Math.PI + 0.9 - Math.Sin(u * 5.0 + 0.4) * 0.9;
        int h1x = lx + 2 * s + 2, h1y = 200 - 5 * s, h2x = rx - 2, h2y = 200 - 5 * s;
        Glow(h1x, h1y, h1x + Math.Cos(a1) * 60, h1y + Math.Sin(a1) * 60, Rgb(127, 232, 255));
        Glow(h2x, h2y, h2x + Math.Cos(a2) * 60, h2y + Math.Sin(a2) * 60, Rgb(255, 150, 60));
        double clash = Math.Sin(u * 5.0);
        if (clash > 0.75)
        {
            int cx = (h1x + h2x) / 2, cy = 200 - 9 * s;
            var r = new Random((int)(u * 30));
            for (int i = 0; i < 30; i++) { double a = r.NextDouble() * Math.PI * 2, d = r.NextDouble() * 18; Set(cx + (int)(Math.Cos(a) * d), cy + (int)(Math.Sin(a) * d), 0xFFFFFF | unchecked((int)0xFF000000)); }
            Disc(cx, cy, 6, 0xFFFFFF, 0.6);
        }
    }

    static void Laser(double u)
    {
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) px[y * W + x] = Mix(Rgb(8, 10, 30), Rgb(16, 24, 60), y / (double)H);
        for (int y = 180; y < H; y++) for (int x = 0; x < W; x++) px[y * W + x] = ((x / 24 + y / 12) % 2 == 0) ? Rgb(18, 22, 50) : Rgb(14, 16, 40);
        for (int x = 0; x < W; x += 24) Line(x, 180, W / 2 + (x - W / 2) * 2.2, H, Rgb(255, 80, 200), 0.2);
        // cover blocks
        Rect(200, 150, 30, 30, Rgb(40, 44, 80)); Rect(200, 150, 30, 2, Rgb(255, 80, 200));
        Rect(260, 160, 24, 20, Rgb(40, 44, 80)); Rect(260, 160, 24, 2, Rgb(80, 200, 255));
        int s = 5;
        Person(90, 210, s, Rgb(230, 190, 150), Rgb(220, 50, 50), Rgb(60, 20, 20), true, 0);
        Person(140, 200, s, Rgb(230, 190, 150), Rgb(220, 50, 50), Rgb(60, 20, 20), true, 0);
        Person(360, 210, s, Rgb(230, 190, 150), Rgb(50, 110, 230), Rgb(20, 30, 70), false, 0);
        Person(320, 198, s, Rgb(230, 190, 150), Rgb(50, 110, 230), Rgb(20, 30, 70), false, 0);
        // beams flashing back and forth
        double[] shots = { 0.15, 0.45, 0.7, 1.0, 1.3, 1.55, 1.85, 2.15, 2.4, 2.7, 3.0, 3.25 };
        for (int i = 0; i < shots.Length; i++)
        {
            double d = u - shots[i];
            if (d < 0 || d > 0.28) continue;
            bool red = i % 2 == 0;
            int x0 = red ? (i % 4 == 0 ? 100 : 150) : (i % 4 == 1 ? 360 : 320), y0 = red ? 180 : 175;
            int x1 = red ? 340 : 110, y1 = red ? 170 + i * 3 : 182;
            Glow(x0, y0, x1, y1, red ? Rgb(255, 60, 60) : Rgb(60, 160, 255));
            Disc(x1, y1, 5 * (1 - d / 0.25), red ? Rgb(255, 120, 120) : Rgb(120, 200, 255), 0.8);
        }
    }

    static void Retro(double u)
    {
        // the old blue sky, square sun, blocky clouds and grass hills
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) px[y * W + x] = Mix(Rgb(120, 168, 255), Rgb(180, 210, 255), y / 180.0);
        Rect(380, 40, 24, 24, Rgb(255, 255, 220));
        for (int i = 0; i < 5; i++) { int cx = (int)((i * 130 + u * 10) % 560) - 60; Rect(cx, 30 + i % 3 * 18, 60, 10, Rgb(250, 250, 255)); Rect(cx + 10, 24 + i % 3 * 18, 36, 6, Rgb(250, 250, 255)); }
        int bs = 8;
        double scroll = u * 14;
        for (int col = -1; col < W / bs + 2; col++)
        {
            double wx = col + scroll / bs;
            int h = 9 + (int)Math.Round(Math.Sin(wx * 0.25) * 3 + Math.Sin(wx * 0.11) * 2);
            int x = col * bs - (int)(scroll % bs);
            for (int r = 0; r < h; r++)
            {
                int y = H - (r + 1) * bs;
                bool topBlock = r == h - 1;
                for (int yy = 0; yy < bs; yy++) for (int xx = 0; xx < bs; xx++)
                {
                    int n = ((xx * 7 + yy * 13 + col * 5 + r * 3) % 5);
                    int c = topBlock ? (yy < 2 ? Rgb(100 + n * 6, 170 + n * 5, 60) : Rgb(130 + n * 5, 90 + n * 4, 60)) : (r < h - 4 ? Rgb(120 + n * 6, 120 + n * 6, 120 + n * 6) : Rgb(130 + n * 5, 90 + n * 4, 60));
                    Set(x + xx, y + yy, c);
                }
            }
            // oak trees every few columns
            if (((int)Math.Floor(wx)) % 9 == 0)
            {
                int ty = H - h * bs;
                Rect(x + 2, ty - 32, 4, 32, Rgb(110, 80, 50));
                Rect(x - 10, ty - 50, 28, 22, Rgb(60, 140, 40)); Rect(x - 4, ty - 58, 16, 8, Rgb(70, 150, 50));
            }
        }
    }

    static void Sky(double u)
    {
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) px[y * W + x] = Mix(Rgb(90, 150, 255), Rgb(200, 225, 255), y / (double)H);
        for (int i = 0; i < 6; i++) { int cx = (int)((i * 110 - u * 18) % 560 + 560) % 560 - 60; Rect(cx, 200 + i % 3 * 14, 70, 10, Rgb(245, 248, 255)); }
        // the floating island
        int ix = 170, iy = 120 + (int)(Math.Sin(u * 1.8) * 4), bs = 8;
        int[] widths = { 12, 10, 8, 5, 3 };
        for (int r = 0; r < widths.Length; r++)
            for (int c = 0; c < widths[r]; c++)
            {
                int x = ix + (12 - widths[r]) * bs / 2 + c * bs, y = iy + r * bs;
                for (int yy = 0; yy < bs; yy++) for (int xx = 0; xx < bs; xx++)
                {
                    int n = (xx * 7 + yy * 3 + c * 5 + r) % 5;
                    int col = r == 0 ? (yy < 2 ? Rgb(100 + n * 6, 175, 60) : Rgb(135, 95 + n * 3, 60)) : Rgb(120 + n * 5, 85 + n * 4, 60);
                    Set(x + xx, y + yy, col);
                }
            }
        Rect(ix + 30, iy - 34, 6, 34, Rgb(110, 80, 50)); Rect(ix + 14, iy - 56, 38, 26, Rgb(60, 140, 40));
        Rect(ix + 70, iy - 8, 8, 8, Rgb(150, 110, 60));                                  // a chest
        // a big survival world in the distance (right side)
        for (int x = 300; x < W; x++) { int h = 40 + (int)(Math.Sin(x * 0.05) * 10 + Math.Sin(x * 0.13) * 5); for (int y = H - h; y < H; y++) px[y * W + x] = y < H - h + 4 ? Rgb(90, 160, 60) : Rgb(110, 90, 70); }
        Person(ix + 50, iy, 3, Rgb(230, 190, 150), Rgb(60, 160, 220), Rgb(40, 50, 100), true, 0);
    }

    static void Pass(double u)
    {
        Space(u + 29, 80 + u * 8, 1);
        // 30 segments filling
        int x0 = 60, y0 = 190, seg = 11;
        double fill = Math.Min(30, u / 3.0 * 30);
        for (int i = 0; i < 30; i++)
        {
            int c = i < fill ? Mix(Rgb(138, 92, 255), Rgb(79, 140, 255), i / 30.0) : Rgb(40, 40, 60);
            Rect(x0 + i * (seg + 1), y0, seg, 10, c);
            if (i < fill && fill - i < 1.2) Rect(x0 + i * (seg + 1), y0 - 1, seg, 12, 0xFFFFFF | unchecked((int)0xFF000000));
        }
        // stars popping up
        var r = new Random(5);
        for (int i = 0; i < 14; i++)
        {
            double t0 = i * 0.22, d = u - t0;
            if (d < 0) continue;
            int x = 60 + r.Next(360), y = 70 + r.Next(90);
            double sc = Math.Min(1, d / 0.2) * (1 + Math.Sin(d * 6) * 0.1);
            int sz = (int)(5 * sc);
            Line(x - sz, y, x + sz, y, Rgb(255, 213, 74), 1); Line(x, y - sz, x, y + sz, Rgb(255, 213, 74), 1);
            Line(x - sz / 2, y - sz / 2, x + sz / 2, y + sz / 2, Rgb(255, 240, 180), 0.8); Line(x - sz / 2, y + sz / 2, x + sz / 2, y - sz / 2, Rgb(255, 240, 180), 0.8);
        }
    }

    static void Orion(double u)
    {
        Space(u + 32, 120 + u * 5, 1);
        // crystal platform
        int cx = W / 2, cy = 200;
        for (int y = -14; y <= 14; y++) for (int x = -150; x <= 150; x++)
        {
            if (x * x / (150.0 * 150) + y * y / (14.0 * 14) > 1) continue;
            int c = (Math.Abs(Math.Abs(x) - Math.Abs(y) * 10) < 4) ? Rgb(127, 255, 232) : Rgb(40, 36, 60);
            Set(cx + x, cy + y, c);
        }
        for (int y = 14; y < 50; y++) { int w = (int)(150 * Math.Sqrt(1 - y / 50.0)); for (int x = -w; x <= w; x++) Set(cx + x, cy + y, ((x + y) % 7 == 0) ? Rgb(150, 90, 220) : Rgb(50, 40, 70)); }
        // spires
        for (int i = -3; i <= 3; i++) { if (i == 0) continue; int x = cx + i * 40; int h = 40 + Math.Abs(i) % 2 * 20; Rect(x, cy - h, 5, h, Rgb(60, 50, 90)); Rect(x, cy - h - 3, 5, 3, Rgb(190, 140, 255)); }
        // core with beam
        double pulse = 1 + Math.Sin(u * 4) * 0.15;
        Line(cx, cy - 8, cx, cy - 120, Rgb(200, 160, 255), 0.6);
        Disc(cx, cy - 90, 9 * pulse, Rgb(190, 140, 255), 0.9); Disc(cx, cy - 90, 5 * pulse, 0xFFFFFF, 0.9);
        for (int i = 0; i < 10; i++) { double a = u * 1.5 + i * Math.PI / 5; Disc(cx + Math.Cos(a) * 24, cy - 90 + Math.Sin(a * 2) * 4, 2, i % 2 == 0 ? Rgb(127, 255, 232) : Rgb(190, 140, 255), 0.9); }
        // Ami: a translucent blue hologram girl with a lab coat
        int ax = cx + 70, ay = cy - 10;
        double fl = 0.55 + 0.15 * Math.Sin(u * 13);
        RectA(ax, ay - 30, 10, 18, Rgb(150, 230, 255), fl);       // coat
        RectA(ax + 1, ay - 12, 3, 12, Rgb(110, 200, 255), fl); RectA(ax + 6, ay - 12, 3, 12, Rgb(110, 200, 255), fl);
        RectA(ax + 1, ay - 39, 8, 9, Rgb(200, 240, 255), fl);     // head
        RectA(ax - 1, ay - 41, 12, 4, Rgb(90, 170, 255), fl); RectA(ax - 1, ay - 37, 3, 14, Rgb(90, 170, 255), fl); RectA(ax + 9, ay - 37, 3, 14, Rgb(90, 170, 255), fl);   // hair
        for (int y = ay - 42; y < ay; y += 2) RectA(ax - 2, y, 14, 1, Rgb(150, 230, 255), 0.15);   // scan lines
        RectA(ax - 4, ay, 18, 2, Rgb(127, 255, 232), 0.6);
    }

    static void Final(double u)
    {
        Space(u + 36, 150 + u * 3, 1);
        Planet(425, 150, 20, Rgb(255, 170, 70), Rgb(110, 30, 10), true);
        Planet(60, 220, 13, Rgb(200, 150, 255), Rgb(50, 20, 100), false);
        int p = 5, lw = LogoWidth("NeyroCraft", p);
        Logo((W - lw) / 2, 50, p, 1, (u % 3.0) / 1.5);
        // box for the address
        RectA(120, 158, 240, 34, Rgb(8, 6, 24), 0.7);
        Rect(120, 158, 240, 1, BLUE); Rect(120, 191, 240, 1, BLUE); Rect(120, 158, 1, 34, BLUE); Rect(359, 158, 1, 34, BLUE);
        Rect(120, 158, 40, 1, ORANGE); Rect(320, 191, 40, 1, ORANGE);
        if (u < 0.5) RectA(0, 0, W, H, 0xFFFFFF, 1 - u / 0.5);
        if (u > LEN - 36 - 1.2) RectA(0, 0, W, H, 0, (u - (LEN - 36 - 1.2)) / 1.2);
    }

    // ---------------------------------------------------------------- titles (drawn on the big frame)
    static void Title(Graphics g, string main, Color c, string sub, double u, double dur)
    {
        double inK = Math.Min(1, u / 0.35), outK = Math.Min(1, (dur - u) / 0.3);
        double k = Math.Max(0, Math.Min(inK, outK));
        float x = (float)(110 - (1 - Math.Pow(inK, 0.5)) * 300);
        var f1 = new Font("Segoe UI Black", 84f, FontStyle.Bold, GraphicsUnit.Pixel);
        var f2 = new Font("Segoe UI", 40f, FontStyle.Bold, GraphicsUnit.Pixel);
        int a = (int)(255 * k);
        using (var bg = new SolidBrush(Color.FromArgb((int)(150 * k), 6, 4, 20))) g.FillRectangle(bg, 0, 60, 1920, 200);
        using (var bar = new SolidBrush(Color.FromArgb(a, c))) g.FillRectangle(bar, x - 40, 84, 14, 152);
        g.DrawString(main, f1, new SolidBrush(Color.FromArgb(a, 0, 0, 0)), x + 5, 75);
        g.DrawString(main, f1, new SolidBrush(Color.FromArgb(a, c)), x, 70);
        g.DrawString(sub, f2, new SolidBrush(Color.FromArgb((int)(a * 0.9), 230, 230, 245)), x + 6, 180);
    }

    static void Centre(Graphics g, string s, Font f, Color c, float y, double a)
    {
        var fmt = new StringFormat { Alignment = StringAlignment.Center };
        int al = (int)(255 * Math.Max(0, Math.Min(1, a)));
        g.DrawString(s, f, new SolidBrush(Color.FromArgb(al, 0, 0, 0)), new RectangleF(4, y + 4, 1920, 200), fmt);
        g.DrawString(s, f, new SolidBrush(Color.FromArgb(al, c)), new RectangleF(0, y, 1920, 200), fmt);
    }

    // ---------------------------------------------------------------- render
    /** Draws the pixel layer of the trailer at time t into px (no text overlays). */
    static void Scene(double t)
    {
        if (t < 4) Stars(t);
        else if (t < 8) Warp(t - 4);
        else if (t < 11.5) LogoScene(t - 8);
        else if (t < 15) Races(t - 11.5);
        else if (t < 18.5) Sabers(t - 15);
        else if (t < 22) Laser(t - 18.5);
        else if (t < 25.5) Retro(t - 22);
        else if (t < 29) Sky(t - 25.5);
        else if (t < 32.5) Pass(t - 29);
        else if (t < 36) Orion(t - 32.5);
        else Final(t - 36);
        foreach (double cut in new[] { 11.5, 15, 18.5, 22, 25.5, 29, 32.5 })
            if (t >= cut && t < cut + 0.15) RectA(0, 0, W, H, 0xFFFFFF, 0.6 * (1 - (t - cut) / 0.15));
    }

    /**
     * Frames for the in-game cinema screen: 160×90 (the pixel layer averaged 3×3), colours rounded to
     * 4 bits per channel, gzip file: "NCT1", frames, width, height, fps, then rgb444 shorts.
     */
    public static void ExportCinema(string path, int fps)
    {
        InitStars();
        int w = W / 3, h = H / 3, frames = (int)(LEN * fps);
        using (var fs = File.Create(path))
        using (var gz = new System.IO.Compression.GZipStream(fs, System.IO.Compression.CompressionLevel.Optimal))
        using (var bw = new BinaryWriter(gz))
        {
            bw.Write(new[] { 'N', 'C', 'T', '1' }); bw.Write(frames); bw.Write(w); bw.Write(h); bw.Write(fps);
            for (int f = 0; f < frames; f++)
            {
                Scene(f / (double)fps);
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++)
                    {
                        int r = 0, g = 0, b = 0;
                        for (int dy = 0; dy < 3; dy++) for (int dx = 0; dx < 3; dx++) { int c = px[(y * 3 + dy) * W + x * 3 + dx]; r += R(c); g += Gc(c); b += B(c); }
                        r = (r / 9 + 8) / 17; g = (g / 9 + 8) / 17; b = (b / 9 + 8) / 17;
                        bw.Write((short)((Math.Min(15, r) << 8) | (Math.Min(15, g) << 4) | Math.Min(15, b)));
                    }
            }
        }
    }

    public static void Render(string dir)
    {
        Directory.CreateDirectory(dir);
        InitStars();
        int frames = (int)(LEN * FPS);
        var small = new Bitmap(W, H, PixelFormat.Format32bppArgb);
        var big = new Bitmap(W * K, H * K, PixelFormat.Format24bppRgb);
        var jpg = Array.Find(ImageCodecInfo.GetImageEncoders(), e => e.MimeType == "image/jpeg");
        var ep = new EncoderParameters(1); ep.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 93L);
        var fTitle = new Font("Segoe UI Black", 56f, FontStyle.Bold, GraphicsUnit.Pixel);
        var fBig = new Font("Segoe UI Black", 72f, FontStyle.Bold, GraphicsUnit.Pixel);
        var fAddr = new Font("Consolas", 58f, FontStyle.Bold, GraphicsUnit.Pixel);
        var fSub = new Font("Segoe UI", 40f, FontStyle.Bold, GraphicsUnit.Pixel);
        for (int f = 0; f < frames; f++)
        {
            double t = f / (double)FPS;
            if (t < 4) Stars(t);
            else if (t < 8) Warp(t - 4);
            else if (t < 11.5) LogoScene(t - 8);
            else if (t < 15) Races(t - 11.5);
            else if (t < 18.5) Sabers(t - 15);
            else if (t < 22) Laser(t - 18.5);
            else if (t < 25.5) Retro(t - 22);
            else if (t < 29) Sky(t - 25.5);
            else if (t < 32.5) Pass(t - 29);
            else if (t < 36) Orion(t - 32.5);
            else Final(t - 36);
            // white blink on each cut
            foreach (double cut in new[] { 11.5, 15, 18.5, 22, 25.5, 29, 32.5 })
                if (t >= cut && t < cut + 0.15) RectA(0, 0, W, H, 0xFFFFFF, 0.6 * (1 - (t - cut) / 0.15));

            var bd = small.LockBits(new Rectangle(0, 0, W, H), ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
            Marshal.Copy(px, 0, bd.Scan0, px.Length);
            small.UnlockBits(bd);
            using (var g = Graphics.FromImage(big))
            {
                g.InterpolationMode = InterpolationMode.NearestNeighbor;
                g.PixelOffsetMode = PixelOffsetMode.Half;
                g.DrawImage(small, new Rectangle(0, 0, W * K, H * K));
                g.PixelOffsetMode = PixelOffsetMode.Default;
                g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
                if (t >= 1.0 && t < 4) Centre(g, "где-то в глубоком космосе…", fSub, Color.FromArgb(200, 210, 240), 860, Math.Min((t - 1) / 0.6, (4 - t) / 0.5));
                if (t >= 9.2 && t < 11.5) Centre(g, "сервер на космическом корабле", fTitle, Color.FromArgb(225, 230, 250), 700, Math.Min((t - 9.2) / 0.4, (11.5 - t) / 0.3));
                if (t >= 11.5 && t < 15) Title(g, "ГОНКИ НА СПИДЕРАХ", Color.FromArgb(255, 160, 64), "планета Терракс · трасса 1,5 км · таймер и рекорды", t - 11.5, 3.5);
                if (t >= 15 && t < 18.5) Title(g, "СВЕТОВЫЕ МЕЧИ", Color.FromArgb(127, 232, 255), "дуэли · толчок · молния · двойной прыжок", t - 15, 3.5);
                if (t >= 18.5 && t < 22) Title(g, "ЛАЗЕРТАГ", Color.FromArgb(255, 90, 90), "красные против синих", t - 18.5, 3.5);
                if (t >= 22 && t < 25.5) Title(g, "MINECRAFT 1.0", Color.FromArgb(85, 255, 85), "настоящий мир 2011 года · старые текстуры и звуки", t - 22, 3.5);
                if (t >= 25.5 && t < 29) Title(g, "SKYBLOCK И ВЫЖИВАНИЕ", Color.FromArgb(85, 255, 255), "свой остров в небе и большой мир", t - 25.5, 3.5);
                if (t >= 29 && t < 32.5) Title(g, "STARPASS", Color.FromArgb(180, 140, 255), "30 уровней наград · звёзды каждый день", t - 29, 3.5);
                if (t >= 32.5 && t < 36) Title(g, "СТАНЦИЯ «ОРИОН»", Color.FromArgb(224, 123, 255), "второй хаб · голограмма Ами · варп-прыжки", t - 32.5, 3.5);
                if (t >= 36)
                {
                    double u = t - 36, a = Math.Min(1, (u - 0.4) / 0.5) * Math.Min(1, (LEN - t) / 1.2);
                    Centre(g, "ЗАХОДИ НА БОРТ!", fBig, Color.FromArgb(255, 213, 74), 470, a);
                    Centre(g, "neyrocraft.duckdns.org", fAddr, Color.White, 648, Math.Min(1, (u - 0.8) / 0.5) * Math.Min(1, (LEN - t) / 1.2));
                    Centre(g, "версия 26.3  ·  пиратка ✓  ·  вход по паролю", fSub, Color.FromArgb(200, 210, 240), 800, Math.Min(1, (u - 1.2) / 0.5) * Math.Min(1, (LEN - t) / 1.2));
                }
            }
            big.Save(Path.Combine(dir, string.Format("f{0:D5}.jpg", f)), jpg, ep);
        }
    }
}
