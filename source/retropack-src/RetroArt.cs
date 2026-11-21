using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;

// NeyroCraft retro pack: original 16x16 textures drawn procedurally in a 2011-like style
// (high-contrast noise, saturated colours, simple shapes). Nothing is copied from Mojang art.
public static class RetroArt
{
    static string outDir;
    static Random R;

    public static void Generate(string root)
    {
        outDir = root;
        Directory.CreateDirectory(Path.Combine(root, "assets/minecraft/textures/block"));
        Directory.CreateDirectory(Path.Combine(root, "assets/minecraft/textures/item"));
        Blocks();
        Items();
    }

    // ------------------------------------------------------------ helpers

    class Img { public Color[,] p = new Color[16, 16]; }
    static Img New(Color c) { var i = new Img(); for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) i.p[x, y] = c; return i; }
    static Img Clear() { return New(Color.FromArgb(0, 0, 0, 0)); }
    static int Cl(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }
    static Color Sh(Color c, int d) { return Color.FromArgb(c.A, Cl(c.R + d), Cl(c.G + d), Cl(c.B + d)); }
    static Color Rgb(int r, int g, int b) { return Color.FromArgb(255, r, g, b); }
    static Color Mix(Color a, Color b, double t) { return Color.FromArgb(255, Cl((int)(a.R + (b.R - a.R) * t)), Cl((int)(a.G + (b.G - a.G) * t)), Cl((int)(a.B + (b.B - a.B) * t))); }

    static void Save(Img img, string kind, string name)
    {
        using (var bmp = new Bitmap(16, 16, PixelFormat.Format32bppArgb))
        {
            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) bmp.SetPixel(x, y, img.p[x, y]);
            bmp.Save(Path.Combine(outDir, "assets/minecraft/textures/" + kind + "/" + name + ".png"), ImageFormat.Png);
        }
    }

    /** grainy base: each pixel base +- amp, with occasional darker/lighter specks */
    static Img Grain(Color c, int amp, int seed, double speck = 0.08, int speckAmp = 30)
    {
        R = new Random(seed);
        var i = new Img();
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++)
        {
            int d = R.Next(-amp, amp + 1);
            if (R.NextDouble() < speck) d += R.Next(2) == 0 ? -speckAmp : speckAmp / 2;
            i.p[x, y] = Sh(c, d);
        }
        return i;
    }

    /** tiling Voronoi: cell id + distance to edge */
    static void Voronoi(int seed, int cells, out int[,] id, out double[,] edge)
    {
        var r = new Random(seed);
        var pts = new List<PointF>();
        for (int k = 0; k < cells; k++) pts.Add(new PointF((float)(r.NextDouble() * 16), (float)(r.NextDouble() * 16)));
        id = new int[16, 16]; edge = new double[16, 16];
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++)
        {
            double b1 = 1e9, b2 = 1e9; int bi = 0;
            for (int k = 0; k < pts.Count; k++)
                for (int ox = -16; ox <= 16; ox += 16) for (int oy = -16; oy <= 16; oy += 16)
                {
                    double dx = x + 0.5 - (pts[k].X + ox), dy = y + 0.5 - (pts[k].Y + oy), d = Math.Sqrt(dx * dx + dy * dy);
                    if (d < b1) { b2 = b1; b1 = d; bi = k; } else if (d < b2) b2 = d;
                }
            id[x, y] = bi; edge[x, y] = b2 - b1;
        }
    }

    static Img Cells(Color baseC, int seed, int cells, int cellVar, Color mortar, double mortarW)
    {
        int[,] id; double[,] edge; Voronoi(seed, cells, out id, out edge);
        var r = new Random(seed + 99); var shade = new int[cells]; for (int k = 0; k < cells; k++) shade[k] = r.Next(-cellVar, cellVar + 1);
        var i = new Img(); var g = new Random(seed + 7);
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++)
        {
            if (edge[x, y] < mortarW) i.p[x, y] = Sh(mortar, g.Next(-6, 7));
            else
            {
                int hl = edge[x, y] < mortarW + 1.0 ? -10 : 0;
                i.p[x, y] = Sh(baseC, shade[id[x, y]] + hl + g.Next(-7, 8));
            }
        }
        return i;
    }

    static Img Ore(Img stone, Color ore, int seed)
    {
        var i = new Img(); for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) i.p[x, y] = stone.p[x, y];
        var r = new Random(seed);
        for (int k = 0; k < 5; k++)
        {
            int cx = 2 + r.Next(12), cy = 2 + r.Next(12);
            int n = 3 + r.Next(3);
            for (int j = 0; j < n; j++)
            {
                int x = cx + r.Next(-1, 2), y = cy + r.Next(-1, 2);
                if (x < 0 || y < 0 || x > 15 || y > 15) continue;
                i.p[x, y] = j == 0 ? Sh(ore, 45) : Sh(ore, r.Next(-25, 10));
                if (x + 1 < 16 && y + 1 < 16 && j == 0) i.p[x + 1, y + 1] = Sh(ore, -45);
            }
        }
        return i;
    }

    // ------------------------------------------------------------ blocks

    static void Blocks()
    {
        Color stoneC = Rgb(122, 122, 122);
        var stone = Grain(stoneC, 9, 1, 0.12, 22);
        var cr = new Random(11);
        for (int k = 0; k < 6; k++) { int x = cr.Next(16), y = cr.Next(16), len = 2 + cr.Next(3); for (int j = 0; j < len; j++) stone.p[(x + j) % 16, y] = Sh(stoneC, -26); }
        Save(stone, "block", "stone");

        Save(Cells(Rgb(118, 118, 118), 21, 9, 18, Rgb(70, 70, 70), 0.9), "block", "cobblestone");
        var mossy = Cells(Rgb(118, 118, 118), 21, 9, 18, Rgb(70, 70, 70), 0.9);
        var mr = new Random(23);
        for (int k = 0; k < 70; k++) { int x = mr.Next(16), y = mr.Next(16); mossy.p[x, y] = Mix(mossy.p[x, y], Rgb(72, 120, 48), 0.75); }
        Save(mossy, "block", "mossy_cobblestone");

        Color dirtC = Rgb(134, 96, 66);
        var dirt = Grain(dirtC, 12, 3, 0.15, 30);
        Save(dirt, "block", "dirt");

        var grassTop = Grain(Rgb(150, 150, 150), 22, 5, 0.18, 40);
        Save(grassTop, "block", "grass_block_top");
        var side = Grain(dirtC, 12, 3, 0.15, 30);
        var overlay = Clear();
        var gr = new Random(7);
        for (int x = 0; x < 16; x++)
        {
            int depth = 3 + gr.Next(3);
            for (int y = 0; y < depth; y++)
            {
                int v = 150 + gr.Next(-30, 25);
                overlay.p[x, y] = Rgb(v, v, v);
                side.p[x, y] = Rgb(96, 158, 58);
            }
        }
        Save(side, "block", "grass_block_side");
        Save(overlay, "block", "grass_block_side_overlay");

        Save(Grain(Rgb(220, 208, 160), 8, 9, 0.12, 20), "block", "sand");
        Save(Cells(Rgb(132, 124, 120), 31, 11, 28, Rgb(92, 86, 82), 0.6), "block", "gravel");
        Save(Cells(Rgb(80, 80, 80), 41, 10, 45, Rgb(30, 30, 30), 0.8), "block", "bedrock");
        Save(Grain(Rgb(160, 166, 178), 8, 43, 0.1, 18), "block", "clay");

        Save(Ore(stone, Rgb(40, 40, 40), 101), "block", "coal_ore");
        Save(Ore(stone, Rgb(214, 170, 138), 102), "block", "iron_ore");
        Save(Ore(stone, Rgb(250, 228, 70), 103), "block", "gold_ore");
        Save(Ore(stone, Rgb(90, 232, 240), 104), "block", "diamond_ore");
        Save(Ore(stone, Rgb(230, 20, 20), 105), "block", "redstone_ore");
        Save(Ore(stone, Rgb(36, 72, 190), 106), "block", "lapis_ore");

        // planks: 4 boards, staggered joints
        Color plankC = Rgb(160, 130, 80);
        var planks = Grain(plankC, 7, 51, 0.05, 15);
        var pr = new Random(52);
        for (int b = 0; b < 4; b++)
        {
            int y0 = b * 4; int joint = pr.Next(16);
            for (int x = 0; x < 16; x++)
            {
                planks.p[x, y0 + 3] = Sh(plankC, -45);
                if (pr.NextDouble() < 0.25) planks.p[x, y0 + 1] = Sh(plankC, -14);
            }
            for (int y = y0; y < y0 + 3; y++) planks.p[joint, y] = Sh(plankC, -38);
        }
        Save(planks, "block", "oak_planks");

        Log("oak", Rgb(104, 82, 50), Rgb(170, 138, 86), 61, false);
        Log("spruce", Rgb(62, 44, 26), Rgb(122, 92, 56), 62, false);
        Log("birch", Rgb(216, 216, 206), Rgb(196, 176, 120), 63, true);

        Leaves("oak_leaves", 71); Leaves("birch_leaves", 72); Leaves("spruce_leaves", 73);

        // glass: clear with a frame and two glints
        var glass = Clear();
        Color frame = Color.FromArgb(255, 200, 225, 230);
        for (int k = 0; k < 16; k++) { glass.p[k, 0] = frame; glass.p[k, 15] = frame; glass.p[0, k] = frame; glass.p[15, k] = frame; }
        for (int k = 0; k < 4; k++) { glass.p[3 + k, 6 - k] = Color.FromArgb(200, 255, 255, 255); glass.p[8 + k, 12 - k] = Color.FromArgb(160, 255, 255, 255); }
        Save(glass, "block", "glass");

        // bricks
        Color brickC = Rgb(150, 74, 58), mortarC = Rgb(196, 186, 176);
        var bricks = Grain(brickC, 10, 81, 0.1, 20);
        for (int row = 0; row < 4; row++)
        {
            int y0 = row * 4, off = row % 2 == 0 ? 0 : 4;
            for (int x = 0; x < 16; x++) bricks.p[x, y0 + 3] = Sh(mortarC, new Random(x + row).Next(-8, 8));
            for (int y = y0; y < y0 + 3; y++) { bricks.p[off % 16, y] = mortarC; bricks.p[(off + 8) % 16, y] = mortarC; }
        }
        Save(bricks, "block", "bricks");

        var obs = Grain(Rgb(22, 16, 34), 7, 91, 0.12, -1);
        var or = new Random(92); for (int k = 0; k < 14; k++) obs.p[or.Next(16), or.Next(16)] = Rgb(82, 58, 120);
        Save(obs, "block", "obsidian");

        Color sandstoneC = Rgb(218, 206, 158);
        var ss = Grain(sandstoneC, 6, 111, 0.06, 14);
        for (int x = 0; x < 16; x++) { ss.p[x, 3] = Sh(sandstoneC, -18); ss.p[x, 10] = Sh(sandstoneC, -14); ss.p[x, 15] = Sh(sandstoneC, -24); }
        Save(ss, "block", "sandstone");
        Save(Grain(sandstoneC, 5, 112, 0.04, 12), "block", "sandstone_top");
        Save(Grain(Sh(sandstoneC, -8), 8, 113, 0.08, 16), "block", "sandstone_bottom");

        Save(Grain(Rgb(240, 248, 250), 5, 121, 0.08, -14), "block", "snow");
        var ice = New(Color.FromArgb(170, 150, 190, 245));
        var ir = new Random(123); for (int k = 0; k < 18; k++) ice.p[ir.Next(16), ir.Next(16)] = Color.FromArgb(200, 210, 230, 255);
        Save(ice, "block", "ice");
        Save(Grain(Rgb(232, 232, 232), 6, 131, 0.1, -16), "block", "white_wool");

        // cactus (sides have a transparent 1px edge like the model expects)
        Color cactusC = Rgb(30, 120, 40);
        var cs = Clear(); var cr2 = new Random(141);
        for (int x = 1; x < 15; x++) for (int y = 0; y < 16; y++)
            cs.p[x, y] = Sh(cactusC, (x % 4 == 1 ? -18 : 6) + cr2.Next(-6, 7));
        for (int k = 0; k < 10; k++) cs.p[1 + cr2.Next(14), cr2.Next(16)] = Rgb(230, 230, 200);
        Save(cs, "block", "cactus_side");
        var ct = Clear(); for (int x = 1; x < 15; x++) for (int y = 1; y < 15; y++) ct.p[x, y] = Sh(cactusC, ((x + y) % 5 == 0) ? 18 : -4);
        Save(ct, "block", "cactus_top");
        Save(ct, "block", "cactus_bottom");

        // sugar cane: three jointed stalks
        var cane = Clear();
        foreach (int sx in new[] { 3, 8, 12 })
            for (int y = 0; y < 16; y++)
            {
                bool joint = (y + sx) % 5 == 0;
                cane.p[sx, y] = joint ? Rgb(150, 190, 90) : Rgb(110, 168, 70);
                cane.p[sx + 1, y] = joint ? Rgb(120, 160, 70) : Rgb(80, 130, 50);
            }
        Save(cane, "block", "sugar_cane");

        // short grass (grey: tinted by the biome) and flowers
        var sg = Clear(); var sr = new Random(151);
        for (int b = 0; b < 7; b++)
        {
            int x = 1 + sr.Next(14), h = 6 + sr.Next(9), lean = sr.Next(-1, 2);
            for (int y = 0; y < h; y++) { int xx = Math.Max(0, Math.Min(15, x + (y > h / 2 ? lean : 0))); int v = 120 + sr.Next(60); sg.p[xx, 15 - y] = Rgb(v, v, v); }
        }
        Save(sg, "block", "short_grass");
        Flower("dandelion", Rgb(250, 230, 40), Rgb(200, 160, 20));
        Flower("poppy", Rgb(220, 30, 30), Rgb(150, 10, 10));

        // crafting table, furnace, TNT, bookshelf
        var ctTop = Grain(plankC, 6, 161, 0.04, 12);
        for (int k = 0; k < 16; k++) { ctTop.p[k, 0] = ctTop.p[k, 15] = ctTop.p[0, k] = ctTop.p[15, k] = Sh(plankC, -40); }
        for (int k = 3; k < 13; k++) { ctTop.p[k, 5] = ctTop.p[k, 10] = ctTop.p[5, k] = ctTop.p[10, k] = Sh(plankC, -30); }
        Save(ctTop, "block", "crafting_table_top");
        var ctSide = Copy(planks);
        for (int x = 3; x < 13; x++) for (int y = 2; y < 9; y++) if (x == 3 || x == 12 || y == 2 || y == 8) ctSide.p[x, y] = Rgb(70, 50, 30);
        for (int x = 5; x < 11; x++) ctSide.p[x, 5] = Rgb(150, 150, 150);
        Save(ctSide, "block", "crafting_table_side");
        var ctFront = Copy(ctSide);
        for (int y = 10; y < 15; y++) { ctFront.p[4, y] = Rgb(140, 140, 140); ctFront.p[11, y] = Rgb(110, 80, 50); }
        Save(ctFront, "block", "crafting_table_front");

        var fSide = Cells(Rgb(118, 118, 118), 171, 7, 10, Rgb(84, 84, 84), 0.7);
        Save(fSide, "block", "furnace_side");
        Save(Grain(Rgb(110, 110, 110), 8, 172, 0.1, 20), "block", "furnace_top");
        var fFront = Copy(fSide);
        for (int x = 4; x < 12; x++) for (int y = 7; y < 13; y++) fFront.p[x, y] = (x == 4 || x == 11 || y == 7 || y == 12) ? Rgb(60, 60, 60) : Rgb(24, 24, 24);
        for (int x = 3; x < 13; x++) fFront.p[x, 4] = Rgb(70, 70, 70);
        Save(fFront, "block", "furnace_front");
        var fOn = Copy(fFront);
        var fr = new Random(173); for (int x = 5; x < 11; x++) for (int y = 9; y < 12; y++) fOn.p[x, y] = fr.Next(2) == 0 ? Rgb(255, 160, 30) : Rgb(230, 80, 20);
        Save(fOn, "block", "furnace_front_on");

        var tntS = New(Rgb(200, 40, 30)); var tr = new Random(181);
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) tntS.p[x, y] = Sh(Rgb(200, 40, 30), (x % 4 == 0 ? -40 : 0) + tr.Next(-8, 9));
        for (int x = 0; x < 16; x++) for (int y = 6; y < 10; y++) tntS.p[x, y] = Rgb(232, 232, 222);
        int[,] letters = { { 2, 1, 1, 1, 0, 1, 0, 0, 1, 0, 1, 1, 1, 0 } };
        string word = "###.#..#.###|.#..##.#..#.|.#..#.##..#.";
        string[] rows = word.Split('|');
        for (int ry = 0; ry < 3; ry++) for (int rx = 0; rx < rows[ry].Length; rx++) if (rows[ry][rx] == '#') tntS.p[2 + rx, 6 + ry] = Rgb(30, 30, 30);
        Save(tntS, "block", "tnt_side");
        var tntT = Grain(Rgb(200, 40, 30), 10, 182, 0.05, 15);
        for (int x = 6; x < 10; x++) for (int y = 6; y < 10; y++) tntT.p[x, y] = Rgb(60, 50, 40);
        Save(tntT, "block", "tnt_top");
        Save(tntT, "block", "tnt_bottom");

        var shelf = Copy(planks);
        var br = new Random(191);
        foreach (int y0 in new[] { 1, 9 })
            for (int x = 1; x < 15; x++)
            {
                Color book = new[] { Rgb(150, 30, 30), Rgb(40, 60, 150), Rgb(40, 120, 50), Rgb(170, 140, 60), Rgb(90, 50, 110) }[br.Next(5)];
                int h = 5 + br.Next(2);
                for (int y = y0 + (6 - h); y < y0 + 6; y++) shelf.p[x, y] = (x % 2 == 0) ? book : Sh(book, -25);
            }
        Save(shelf, "block", "bookshelf");

        // torch: stick with a flame
        var torch = Clear();
        // the torch model only shows columns 7-8, rows 6-15: flame on top of that strip
        for (int y = 8; y < 16; y++) { torch.p[7, y] = Rgb(120, 90, 50); torch.p[8, y] = Rgb(90, 66, 36); }
        torch.p[7, 7] = Rgb(255, 210, 80); torch.p[8, 7] = Rgb(255, 150, 30); torch.p[7, 6] = Rgb(255, 250, 200); torch.p[8, 6] = Rgb(255, 220, 110);
        Save(torch, "block", "torch");
    }

    static Img Copy(Img a) { var i = new Img(); for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) i.p[x, y] = a.p[x, y]; return i; }

    static void Log(string wood, Color bark, Color inner, int seed, bool birch)
    {
        var side = Grain(bark, 8, seed, 0.06, 18); var r = new Random(seed + 1);
        for (int x = 0; x < 16; x++)
        {
            if (!birch && x % 3 == 0) for (int y = 0; y < 16; y++) side.p[x, y] = Sh(bark, -22 + r.Next(-5, 6));
        }
        if (birch) for (int k = 0; k < 9; k++) { int x = r.Next(14), y = r.Next(16), w = 2 + r.Next(3); for (int j = 0; j < w; j++) side.p[(x + j) % 16, y] = Rgb(40, 40, 40); }
        Save(side, "block", wood + "_log");
        var top = new Img();
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++)
        {
            int d = Math.Max(Math.Abs(2 * x - 15), Math.Abs(2 * y - 15)) / 2;
            top.p[x, y] = d >= 7 ? Sh(bark, r.Next(-8, 8)) : Sh(inner, (d % 2 == 0 ? -16 : 4) + r.Next(-5, 6));
        }
        Save(top, "block", wood + "_log_top");
    }

    static void Leaves(string name, int seed)
    {
        var r = new Random(seed); var i = Clear();
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++)
        {
            if (r.NextDouble() < 0.22) continue;
            int v = 90 + r.Next(110);
            i.p[x, y] = Rgb(v, v, v);
        }
        Save(i, "block", name);
    }

    static void Flower(string name, Color petal, Color dark)
    {
        var f = Clear();
        for (int y = 8; y < 16; y++) f.p[8, y] = Rgb(50, 140, 40);
        f.p[7, 12] = Rgb(60, 160, 50); f.p[9, 11] = Rgb(60, 160, 50);
        int[][] pts = { new[] { 7, 5 }, new[] { 8, 5 }, new[] { 6, 6 }, new[] { 9, 6 }, new[] { 7, 7 }, new[] { 8, 7 }, new[] { 6, 5 }, new[] { 9, 5 }, new[] { 7, 4 }, new[] { 8, 4 }, new[] { 6, 7 }, new[] { 9, 7 } };
        foreach (var p in pts) f.p[p[0], p[1]] = petal;
        f.p[7, 6] = dark; f.p[8, 6] = dark;
        Save(f, "item" == "x" ? "item" : "block", name);
    }

    // ------------------------------------------------------------ items

    static readonly Dictionary<string, Color[]> MAT = new Dictionary<string, Color[]> {
        { "wooden",  new[]{ Rgb(70, 50, 26),  Rgb(140, 106, 60), Rgb(186, 150, 96) } },
        { "stone",   new[]{ Rgb(60, 60, 60),  Rgb(130, 130, 130), Rgb(176, 176, 176) } },
        { "iron",    new[]{ Rgb(90, 90, 90),  Rgb(200, 200, 200), Rgb(255, 255, 255) } },
        { "golden",  new[]{ Rgb(140, 100, 10), Rgb(240, 210, 40), Rgb(255, 250, 160) } },
        { "diamond", new[]{ Rgb(14, 90, 100), Rgb(70, 220, 230), Rgb(200, 255, 255) } },
    };
    static readonly Color HD = Rgb(70, 50, 26), HL = Rgb(140, 106, 60);

    static bool[,] Mask() { return new bool[16, 16]; }
    static void Line(bool[,] m, int x0, int y0, int x1, int y1, int w)
    {
        int n = Math.Max(Math.Abs(x1 - x0), Math.Abs(y1 - y0));
        for (int i = 0; i <= n; i++)
        {
            int x = (int)Math.Round(x0 + (x1 - x0) * (double)i / n), y = (int)Math.Round(y0 + (y1 - y0) * (double)i / n);
            for (int a = 0; a < w; a++) for (int b = 0; b < w; b++) if (x + a < 16 && y + b < 16 && x + a >= 0 && y + b >= 0) m[x + a, y + b] = true;
        }
    }
    static void Handle(Img img, int x0, int y0, int x1, int y1)
    {
        int n = Math.Max(Math.Abs(x1 - x0), Math.Abs(y1 - y0));
        for (int i = 0; i <= n; i++)
        {
            int x = (int)Math.Round(x0 + (x1 - x0) * (double)i / n), y = (int)Math.Round(y0 + (y1 - y0) * (double)i / n);
            img.p[x, y] = HL; if (x + 1 < 16) img.p[x + 1, y] = HD; if (y + 1 < 16 && img.p[x, y + 1].A == 0) img.p[x, y + 1] = HD;
        }
    }
    static void Paint(Img img, bool[,] m, Color[] c)
    {
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++)
        {
            if (!m[x, y]) continue;
            bool edge = x == 0 || y == 0 || x == 15 || y == 15 || !m[x - 1, y] || !m[x + 1, y] || !m[x, y - 1] || !m[x, y + 1];
            bool lit = !edge && x > 0 && y > 0 && (!m[x - 1, y - 1] || (x > 1 && y > 1 && !m[x - 2, y - 2]));
            img.p[x, y] = edge ? c[0] : lit ? c[2] : c[1];
        }
    }

    static void Items()
    {
        foreach (var kv in MAT)
        {
            string mat = kv.Key; var c = kv.Value;
            // sword: blade on the diagonal, guard, grip
            var s = Clear(); var bm = Mask();
            Line(bm, 5, 9, 13, 1, 2); Line(bm, 13, 1, 14, 1, 1);
            Paint(s, bm, c);
            var gm = Mask(); Line(gm, 2, 9, 6, 13, 1); Line(gm, 3, 9, 6, 12, 1);
            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) if (gm[x, y]) s.p[x, y] = c[0];
            Handle(s, 1, 14, 3, 12);
            Save(s, "item", mat + "_sword");

            // pickaxe: arched head + handle
            var p = Clear(); Handle(p, 2, 13, 10, 5);
            var pm = Mask(); Line(pm, 4, 2, 9, 1, 2); Line(pm, 9, 1, 13, 4, 2); Line(pm, 13, 4, 14, 9, 2); Line(pm, 3, 2, 3, 4, 2); Line(pm, 13, 9, 14, 11, 1);
            Paint(p, pm, c); Save(p, "item", mat + "_pickaxe");

            // axe: blade block near the top
            var a = Clear(); Handle(a, 3, 13, 10, 6);
            var am = Mask(); Line(am, 8, 2, 11, 2, 2); Line(am, 7, 3, 12, 3, 2); Line(am, 7, 4, 13, 5, 2); Line(am, 10, 6, 13, 7, 2);
            Paint(a, am, c); Save(a, "item", mat + "_axe");

            // shovel: rounded head
            var sh = Clear(); Handle(sh, 2, 13, 9, 6);
            var shm = Mask(); for (int x = 9; x < 15; x++) for (int y = 1; y < 7; y++) { double dx = x - 11.6, dy = y - 3.6; if (dx * dx + dy * dy < 7.5) shm[x, y] = true; }
            Paint(sh, shm, c); Save(sh, "item", mat + "_shovel");

            // hoe: short L head
            var h = Clear(); Handle(h, 2, 13, 10, 5);
            var hm = Mask(); Line(hm, 7, 2, 12, 2, 2); Line(hm, 12, 2, 12, 5, 2);
            Paint(h, hm, c); Save(h, "item", mat + "_hoe");
        }

        var stick = Clear(); Handle(stick, 3, 12, 12, 3); Save(stick, "item", "stick");

        var coal = Clear(); var cm = Mask(); for (int x = 3; x < 13; x++) for (int y = 4; y < 13; y++) { double dx = x - 7.8, dy = y - 8.2; if (dx * dx * 0.9 + dy * dy < 17 + (x * 7 + y * 3) % 5) cm[x, y] = true; }
        Paint(coal, cm, new[] { Rgb(16, 16, 16), Rgb(44, 44, 44), Rgb(96, 96, 96) }); Save(coal, "item", "coal");

        var dia = Clear(); var dm = Mask();
        for (int y = 3; y < 13; y++) { int half = y < 7 ? (y - 2) + 2 : 12 - y; for (int x = 8 - half; x <= 7 + half; x++) if (x >= 0 && x < 16) dm[x, y] = true; }
        Paint(dia, dm, MAT["diamond"]); Save(dia, "item", "diamond");

        foreach (var ing in new[] { new { n = "iron_ingot", c = MAT["iron"] }, new { n = "gold_ingot", c = MAT["golden"] } })
        {
            var im = Mask(); for (int y = 6; y < 11; y++) for (int x = 2 + (10 - y); x < 13 + (10 - y) - 1; x++) if (x < 16) im[x, y] = true;
            var ii = Clear(); Paint(ii, im, ing.c); Save(ii, "item", ing.n);
        }

        var apple = Clear(); var apm = Mask(); for (int x = 2; x < 14; x++) for (int y = 4; y < 15; y++) { double dx = x - 7.5, dy = y - 9.3; if (dx * dx + dy * dy * 1.1 < 26) apm[x, y] = true; }
        Paint(apple, apm, new[] { Rgb(110, 10, 10), Rgb(210, 30, 30), Rgb(255, 140, 120) });
        apple.p[8, 2] = Rgb(90, 60, 30); apple.p[8, 3] = Rgb(90, 60, 30); apple.p[9, 2] = Rgb(60, 150, 40); apple.p[10, 1] = Rgb(60, 150, 40);
        Save(apple, "item", "apple");
    }
}
