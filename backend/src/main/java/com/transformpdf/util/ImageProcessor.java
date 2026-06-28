package com.transformpdf.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/**
 * CamScanner-style document scanner.
 *
 * Detection: Canny edge → contour finding → polygon approximation → largest quad.
 * Enhancement: background normalization → Sauvola adaptive threshold → sharpen.
 */
@Slf4j
@Component
public class ImageProcessor {

    // ======================== Public API ========================

    public BufferedImage applyScanEffect(BufferedImage original) {
        log.info("=== Scan effect start: {}x{} ===", original.getWidth(), original.getHeight());

        // Downscale for detection
        int maxDim = 800;
        double scale = 1.0;
        BufferedImage workImg = original;
        if (original.getWidth() > maxDim || original.getHeight() > maxDim) {
            scale = Math.min((double) maxDim / original.getWidth(),
                             (double) maxDim / original.getHeight());
            workImg = resize(original,
                    (int) (original.getWidth() * scale),
                    (int) (original.getHeight() * scale));
        }

        // Detect document via edge-based approach
        int[][] corners = detectByEdges(workImg);

        if (corners != null) {
            int[][] fullCorners = new int[4][2];
            int ow = original.getWidth(), oh = original.getHeight();
            for (int i = 0; i < 4; i++) {
                fullCorners[i][0] = clamp((int) (corners[i][0] / scale), 0, ow - 1);
                fullCorners[i][1] = clamp((int) (corners[i][1] / scale), 0, oh - 1);
            }
            log.info("Document corners: TL({},{}) TR({},{}) BR({},{}) BL({},{})",
                    fullCorners[0][0], fullCorners[0][1], fullCorners[1][0], fullCorners[1][1],
                    fullCorners[2][0], fullCorners[2][1], fullCorners[3][0], fullCorners[3][1]);

            BufferedImage warped = perspectiveTransform(original, fullCorners);
            return enhanceScan(warped);
        }

        log.warn("Edge detection failed, trying threshold detection");
        corners = detectByThreshold(workImg);
        if (corners != null) {
            int[][] fullCorners = new int[4][2];
            int ow = original.getWidth(), oh = original.getHeight();
            for (int i = 0; i < 4; i++) {
                fullCorners[i][0] = clamp((int) (corners[i][0] / scale), 0, ow - 1);
                fullCorners[i][1] = clamp((int) (corners[i][1] / scale), 0, oh - 1);
            }
            BufferedImage warped = perspectiveTransform(original, fullCorners);
            return enhanceScan(warped);
        }

        log.warn("All detection failed, enhancing full image");
        return enhanceScan(original);
    }

    public BufferedImage applyScanEffect(BufferedImage original, int[][] corners) {
        log.info("=== Manual scan: {}x{} ===", original.getWidth(), original.getHeight());
        int ow = original.getWidth(), oh = original.getHeight();
        int[][] fullCorners = new int[4][2];
        for (int i = 0; i < 4; i++) {
            fullCorners[i][0] = clamp(corners[i][0], 0, ow - 1);
            fullCorners[i][1] = clamp(corners[i][1], 0, oh - 1);
        }
        BufferedImage warped = perspectiveTransform(original, fullCorners);
        return enhanceScan(warped);
    }

    // ======================== Edge-based Detection (Primary) ========================

    /**
     * Detect document using Canny edge + contour + polygon approximation.
     * This is the standard approach used by OpenCV-based scanners.
     */
    private int[][] detectByEdges(BufferedImage img) {
        BufferedImage gray = toGrayscale(img);
        int w = gray.getWidth(), h = gray.getHeight();

        // Step 1: Gaussian blur to reduce noise
        BufferedImage blurred = gaussianBlur(gray, 5);

        // Step 2: Canny edge detection
        BufferedImage edges = cannyEdgeDetect(blurred, 30, 100);

        // Step 3: Dilate to connect broken edges
        BufferedImage dilated = dilateImage(edges, 3);

        // Step 4: Find contours
        List<List<int[]>> contours = findContours(dilated);
        log.info("Found {} contours", contours.size());

        if (contours.isEmpty()) return null;

        double imgArea = w * h;

        // Step 5: Sort by area, try to find quadrilateral
        contours.sort((a, b) -> Double.compare(contourArea(b), contourArea(a)));

        for (List<int[]> contour : contours) {
            double area = contourArea(contour);
            if (area < imgArea * 0.1) continue; // Skip small contours

            // Try polygon approximation with different epsilon values
            for (double epsFactor : new double[]{0.01, 0.02, 0.03, 0.04, 0.05, 0.06}) {
                double epsilon = epsFactor * contourPerimeter(contour);
                List<int[]> approx = approxPolyDP(contour, epsilon);

                if (approx.size() == 4 && isConvex(approx)) {
                    double quadArea = contourArea(approx);
                    // Must be at least 10% of image and not the whole image
                    if (quadArea > imgArea * 0.1 && quadArea < imgArea * 0.98) {
                        log.info("Found quad: eps={}, area={}/{}, ratio={}",
                                epsFactor, (int) quadArea, (int) imgArea,
                                String.format("%.2f", quadArea / imgArea));
                        return orderCorners(approx);
                    }
                }
            }
        }

        log.info("No valid quad found in edge detection");
        return null;
    }

    // ======================== Threshold-based Detection (Fallback) ========================

    /**
     * Detect document using bright region segmentation.
     * Fallback when edge detection fails.
     */
    private int[][] detectByThreshold(BufferedImage img) {
        BufferedImage gray = toGrayscale(img);
        int w = gray.getWidth(), h = gray.getHeight();

        // Otsu threshold
        int[] hist = new int[256];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                hist[getGray(gray, x, y)]++;
        int otsuTh = otsuThreshold(hist, w * h);
        int threshold = Math.max(otsuTh - 10, 80);

        // Create mask
        boolean[][] mask = new boolean[h][w];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                mask[y][x] = getGray(gray, x, y) > threshold;

        // Morphological cleanup
        mask = morphClose(mask, 7);
        mask = morphOpen(mask, 5);
        mask = morphClose(mask, 3);

        // Find largest component
        List<List<int[]>> components = findConnectedComponents(mask);
        if (components.isEmpty()) return null;

        components.sort((a, b) -> Integer.compare(b.size(), a.size()));
        List<int[]> largest = components.get(0);

        if (largest.size() < w * h * 0.05) return null;

        // Convex hull → find quad
        List<int[]> hull = convexHull(largest);
        if (hull.size() < 4) return boundingBoxCorners(largest, w, h);

        return findQuadFromHull(hull, w, h);
    }

    private int otsuThreshold(int[] hist, int total) {
        double sum = 0;
        for (int i = 0; i < 256; i++) sum += i * hist[i];
        double sumB = 0, wB = 0, maxVar = 0;
        int bestTh = 0;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            double wF = total - wB;
            if (wF == 0) break;
            sumB += t * hist[t];
            double mB = sumB / wB, mF = (sum - sumB) / wF;
            double var = wB * wF * (mB - mF) * (mB - mF);
            if (var > maxVar) { maxVar = var; bestTh = t; }
        }
        return bestTh;
    }

    // ======================== Canny Edge Detection ========================

    private BufferedImage cannyEdgeDetect(BufferedImage gray, int lowTh, int highTh) {
        int w = gray.getWidth(), h = gray.getHeight();
        int[][] gx = {{-1, 0, 1}, {-2, 0, 2}, {-1, 0, 1}};
        int[][] gy = {{-1, -2, -1}, {0, 0, 0}, {1, 2, 1}};
        double[][] mag = new double[h][w];
        double[][] dir = new double[h][w];

        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int sumX = 0, sumY = 0;
                for (int ky = -1; ky <= 1; ky++)
                    for (int kx = -1; kx <= 1; kx++) {
                        int v = getGray(gray, x + kx, y + ky);
                        sumX += v * gx[ky + 1][kx + 1];
                        sumY += v * gy[ky + 1][kx + 1];
                    }
                mag[y][x] = Math.sqrt(sumX * sumX + sumY * sumY);
                dir[y][x] = Math.atan2(sumY, sumX);
            }
        }

        // Non-maximum suppression
        double[][] suppressed = new double[h][w];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                double angle = dir[y][x];
                if (angle < 0) angle += Math.PI;
                double n1, n2;
                if ((angle >= 0 && angle < Math.PI / 8) || (angle >= 7 * Math.PI / 8)) {
                    n1 = mag[y][x - 1]; n2 = mag[y][x + 1];
                } else if (angle < 3 * Math.PI / 8) {
                    n1 = mag[y - 1][x + 1]; n2 = mag[y + 1][x - 1];
                } else if (angle < 5 * Math.PI / 8) {
                    n1 = mag[y - 1][x]; n2 = mag[y + 1][x];
                } else {
                    n1 = mag[y - 1][x - 1]; n2 = mag[y + 1][x + 1];
                }
                suppressed[y][x] = (mag[y][x] >= n1 && mag[y][x] >= n2) ? mag[y][x] : 0;
            }
        }

        // Double threshold + hysteresis
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        boolean[][] strong = new boolean[h][w];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (suppressed[y][x] >= highTh) { strong[y][x] = true; setGray(out, x, y, 255); }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (int y = 1; y < h - 1; y++)
                for (int x = 1; x < w - 1; x++)
                    if (suppressed[y][x] >= lowTh && suppressed[y][x] < highTh && !strong[y][x])
                        for (int dy = -1; dy <= 1 && !strong[y][x]; dy++)
                            for (int dx = -1; dx <= 1 && !strong[y][x]; dx++)
                                if (strong[y + dy][x + dx]) { strong[y][x] = true; setGray(out, x, y, 255); changed = true; }
        }
        return out;
    }

    // ======================== Contour Finding ========================

    private List<List<int[]>> findContours(BufferedImage binary) {
        int w = binary.getWidth(), h = binary.getHeight();
        boolean[][] visited = new boolean[h][w];
        List<List<int[]>> contours = new ArrayList<>();

        for (int y = 1; y < h - 1; y++)
            for (int x = 1; x < w - 1; x++)
                if (getGray(binary, x, y) > 128 && !visited[y][x]) {
                    List<int[]> c = traceContour(binary, visited, x, y, w, h);
                    if (c.size() >= 20) contours.add(c);
                }
        return contours;
    }

    private List<int[]> traceContour(BufferedImage binary, boolean[][] visited,
                                     int sx, int sy, int w, int h) {
        List<int[]> c = new ArrayList<>();
        int[] dx = {1, 1, 0, -1, -1, -1, 0, 1};
        int[] dy = {0, 1, 1, 1, 0, -1, -1, -1};
        int cx = sx, cy = sy, dir = 0, steps = 0;
        do {
            c.add(new int[]{cx, cy});
            visited[cy][cx] = true;
            boolean found = false;
            for (int i = 0; i < 8; i++) {
                int d = (dir + i) % 8;
                int nx = cx + dx[d], ny = cy + dy[d];
                if (nx >= 0 && nx < w && ny >= 0 && ny < h && getGray(binary, nx, ny) > 128) {
                    cx = nx; cy = ny; dir = (d + 5) % 8; found = true; break;
                }
            }
            if (!found) break;
            steps++;
        } while ((cx != sx || cy != sy) && steps < w * h / 2);
        return c;
    }

    // ======================== Polygon Approximation ========================

    private List<int[]> approxPolyDP(List<int[]> c, double eps) {
        if (c.size() < 3) return new ArrayList<>(c);
        int[] first = c.get(0), last = c.get(c.size() - 1);
        double maxD = 0; int maxI = 0;
        for (int i = 1; i < c.size() - 1; i++) {
            double d = pointLineDist(c.get(i), first, last);
            if (d > maxD) { maxD = d; maxI = i; }
        }
        if (maxD > eps) {
            List<int[]> left = approxPolyDP(c.subList(0, maxI + 1), eps);
            List<int[]> right = approxPolyDP(c.subList(maxI, c.size()), eps);
            List<int[]> r = new ArrayList<>(left);
            for (int i = 1; i < right.size(); i++) r.add(right.get(i));
            return r;
        }
        List<int[]> r = new ArrayList<>();
        r.add(first); r.add(last);
        return r;
    }

    private double pointLineDist(int[] p, int[] a, int[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1], len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-6) return Math.sqrt(sq(p[0] - a[0]) + sq(p[1] - a[1]));
        double t = Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (len * len)));
        return Math.sqrt(sq(p[0] - (a[0] + t * dx)) + sq(p[1] - (a[1] + t * dy)));
    }

    // ======================== Geometry ========================

    private double contourArea(List<int[]> c) {
        int n = c.size(); if (n < 3) return 0;
        double a = 0;
        for (int i = 0; i < n; i++) { int j = (i + 1) % n; a += c.get(i)[0] * c.get(j)[1] - c.get(j)[0] * c.get(i)[1]; }
        return Math.abs(a) / 2.0;
    }

    private double contourPerimeter(List<int[]> c) {
        double p = 0; int n = c.size();
        for (int i = 0; i < n; i++) { int j = (i + 1) % n; p += Math.sqrt(sq(c.get(j)[0] - c.get(i)[0]) + sq(c.get(j)[1] - c.get(i)[1])); }
        return p;
    }

    private boolean isConvex(List<int[]> q) {
        if (q.size() != 4) return false;
        int sign = 0;
        for (int i = 0; i < 4; i++) {
            int[] a = q.get(i), b = q.get((i + 1) % 4), c = q.get((i + 2) % 4);
            int cross = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]);
            if (cross != 0) { if (sign == 0) sign = cross > 0 ? 1 : -1; else if ((cross > 0 ? 1 : -1) != sign) return false; }
        }
        return true;
    }

    private int[][] orderCorners(List<int[]> q) {
        int[][] p = new int[4][2];
        for (int i = 0; i < 4; i++) { p[i][0] = q.get(i)[0]; p[i][1] = q.get(i)[1]; }
        return orderCorners(p);
    }

    private int[][] orderCorners(int[][] pts) {
        int[] sums = new int[4], diffs = new int[4];
        for (int i = 0; i < 4; i++) { sums[i] = pts[i][0] + pts[i][1]; diffs[i] = pts[i][0] - pts[i][1]; }
        int tl = 0, tr = 0, br = 0, bl = 0;
        for (int i = 1; i < 4; i++) {
            if (sums[i] < sums[tl]) tl = i;
            if (sums[i] > sums[br]) br = i;
            if (diffs[i] < diffs[tr]) tr = i;
            if (diffs[i] > diffs[bl]) bl = i;
        }
        return new int[][]{pts[tl], pts[tr], pts[br], pts[bl]};
    }

    // ======================== Convex Hull ========================

    private List<int[]> convexHull(List<int[]> pts) {
        if (pts.size() <= 1) return new ArrayList<>(pts);
        List<int[]> sorted = new ArrayList<>(pts);
        sorted.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        List<int[]> lower = new ArrayList<>();
        for (int[] p : sorted) { while (lower.size() >= 2 && cross(lower.get(lower.size()-2), lower.get(lower.size()-1), p) <= 0) lower.remove(lower.size()-1); lower.add(p); }
        List<int[]> upper = new ArrayList<>();
        for (int i = sorted.size()-1; i >= 0; i--) { int[] p = sorted.get(i); while (upper.size() >= 2 && cross(upper.get(upper.size()-2), upper.get(upper.size()-1), p) <= 0) upper.remove(upper.size()-1); upper.add(p); }
        lower.remove(lower.size()-1); lower.addAll(upper); return lower;
    }

    private long cross(int[] o, int[] a, int[] b) { return (long)(a[0]-o[0])*(b[1]-o[1]) - (long)(a[1]-o[1])*(b[0]-o[0]); }

    private int[][] findQuadFromHull(List<int[]> hull, int w, int h) {
        int n = hull.size();
        if (n <= 8) {
            int tlI=0,trI=0,brI=0,blI=0;
            for (int i=1;i<n;i++){int[]p=hull.get(i),tl=hull.get(tlI),tr=hull.get(trI),br=hull.get(brI),bl=hull.get(blI);if(p[0]+p[1]<tl[0]+tl[1])tlI=i;if(p[0]-p[1]>tr[0]-tr[1])trI=i;if(p[0]+p[1]>br[0]+br[1])brI=i;if(p[0]-p[1]<bl[0]-bl[1])blI=i;}
            return new int[][]{hull.get(tlI),hull.get(trI),hull.get(brI),hull.get(blI)};
        }
        double cx=0,cy=0;for(int[]p:hull){cx+=p[0];cy+=p[1];}cx/=n;cy/=n;
        List<double[]>angled=new ArrayList<>();for(int[]p:hull)angled.add(new double[]{p[0],p[1],Math.atan2(p[1]-cy,p[0]-cx)});
        angled.sort(Comparator.comparingDouble(a->a[2]));
        int bi=0,bj=0,bk=0,bl=0;double ba=0;int step=Math.max(1,n/20);
        for(int i=0;i<n;i+=step)for(int j=i+n/4-2;j<=i+n/4+2;j++)for(int k=i+n/2-2;k<=i+n/2+2;k++)for(int l=i+3*n/4-2;l<=i+3*n/4+2;l++){
            int[]a={(int)angled.get(i)[0],(int)angled.get(i)[1]},b={(int)angled.get(j%n)[0],(int)angled.get(j%n)[1]},c={(int)angled.get(k%n)[0],(int)angled.get(k%n)[1]},d={(int)angled.get(l%n)[0],(int)angled.get(l%n)[1]};
            double area=Math.abs((a[0]*b[1]-b[0]*a[1])+(b[0]*c[1]-c[0]*b[1])+(c[0]*d[1]-d[0]*c[1])+(d[0]*a[1]-a[0]*d[1]))/2.0;
            if(area>ba){ba=area;bi=i;bj=j%n;bk=k%n;bl=l%n;}
        }
        return orderCorners(new int[][]{{(int)angled.get(bi)[0],(int)angled.get(bi)[1]},{(int)angled.get(bj)[0],(int)angled.get(bj)[1]},{(int)angled.get(bk)[0],(int)angled.get(bk)[1]},{(int)angled.get(bl)[0],(int)angled.get(bl)[1]}});
    }

    private int[][] boundingBoxCorners(List<int[]> c, int w, int h) {
        int minX=w,minY=h,maxX=0,maxY=0;for(int[]p:c){minX=Math.min(minX,p[0]);minY=Math.min(minY,p[1]);maxX=Math.max(maxX,p[0]);maxY=Math.max(maxY,p[1]);}
        return new int[][]{{minX,minY},{maxX,minY},{maxX,maxY},{minX,maxY}};
    }

    // ======================== Connected Components ========================

    private List<List<int[]>> findConnectedComponents(boolean[][] mask) {
        int h=mask.length,w=mask[0].length;boolean[][] vis=new boolean[h][w];List<List<int[]>> comps=new ArrayList<>();
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(mask[y][x]&&!vis[y][x]){List<int[]>c=bfs(mask,vis,x,y,w,h);if(c.size()>=200)comps.add(c);}
        return comps;
    }

    private List<int[]> bfs(boolean[][] mask, boolean[][] vis, int sx, int sy, int w, int h) {
        List<int[]> c=new ArrayList<>();Queue<int[]>q=new LinkedList<>();q.add(new int[]{sx,sy});vis[sy][sx]=true;
        int[]dx={0,1,0,-1},dy={-1,0,1,0};
        while(!q.isEmpty()){int[]p=q.poll();c.add(p);for(int d=0;d<4;d++){int nx=p[0]+dx[d],ny=p[1]+dy[d];if(nx>=0&&nx<w&&ny>=0&&ny<h&&mask[ny][nx]&&!vis[ny][nx]){vis[ny][nx]=true;q.add(new int[]{nx,ny});}}}
        return c;
    }

    // ======================== Morphology ========================

    private boolean[][] morphClose(boolean[][] m, int s) { return erode(dilate(m,s),s); }
    private boolean[][] morphOpen(boolean[][] m, int s) { return dilate(erode(m,s),s); }

    private boolean[][] dilate(boolean[][] mask, int size) {
        int h=mask.length,w=mask[0].length;boolean[][] out=new boolean[h][w];int half=size/2;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){boolean v=false;for(int ky=-half;ky<=half&&!v;ky++)for(int kx=-half;kx<=half&&!v;kx++)if(mask[clamp(y+ky,0,h-1)][clamp(x+kx,0,w-1)])v=true;out[y][x]=v;}
        return out;
    }

    private BufferedImage dilateImage(BufferedImage binary, int size) {
        int w=binary.getWidth(),h=binary.getHeight();BufferedImage out=new BufferedImage(w,h,BufferedImage.TYPE_BYTE_GRAY);int half=size/2;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int maxVal=0;for(int ky=-half;ky<=half;ky++)for(int kx=-half;kx<=half;kx++)maxVal=Math.max(maxVal,getGray(binary,clamp(x+kx,0,w-1),clamp(y+ky,0,h-1)));setGray(out,x,y,maxVal);}
        return out;
    }

    private boolean[][] erode(boolean[][] mask, int size) {
        int h=mask.length,w=mask[0].length;boolean[][] out=new boolean[h][w];int half=size/2;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){boolean v=true;for(int ky=-half;ky<=half&&v;ky++)for(int kx=-half;kx<=half&&v;kx++)if(!mask[clamp(y+ky,0,h-1)][clamp(x+kx,0,w-1)])v=false;out[y][x]=v;}
        return out;
    }

    // ======================== Preprocessing ========================

    public BufferedImage toGrayscale(BufferedImage src) {
        int w=src.getWidth(),h=src.getHeight();BufferedImage g=new BufferedImage(w,h,BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D gg=g.createGraphics();gg.drawImage(src,0,0,null);gg.dispose();return g;
    }

    private BufferedImage resize(BufferedImage src, int nw, int nh) {
        BufferedImage r=new BufferedImage(nw,nh,src.getType());
        Graphics2D g=r.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src,0,0,nw,nh,null);g.dispose();return r;
    }

    // ======================== Gaussian Blur ========================

    public BufferedImage gaussianBlur(BufferedImage gray, int size) {
        if(size<1)size=1;if(size%2==0)size++;
        float[]k1d=gaussianKernel1D(size);float[]k2d=new float[size*size];
        for(int i=0;i<size;i++)for(int j=0;j<size;j++)k2d[i*size+j]=k1d[i]*k1d[j];
        int w=gray.getWidth(),h=gray.getHeight();BufferedImage out=new BufferedImage(w,h,BufferedImage.TYPE_BYTE_GRAY);int half=size/2;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){float sum=0;for(int ky=0;ky<size;ky++)for(int kx=0;kx<size;kx++){int px=clamp(x+kx-half,0,w-1),py=clamp(y+ky-half,0,h-1);sum+=getGray(gray,px,py)*k2d[ky*size+kx];}setGray(out,x,y,clamp(Math.round(sum),0,255));}
        return out;
    }

    private float[] gaussianKernel1D(int size) {
        float[]k=new float[size];float sigma=size/3.0f,sum=0;int c=size/2;
        for(int i=0;i<size;i++){float x=i-c;k[i]=(float)Math.exp(-(x*x)/(2*sigma*sigma));sum+=k[i];}
        for(int i=0;i<size;i++)k[i]/=sum;return k;
    }

    // ======================== Integral Image ========================

    private long[][] buildIntegralImage(BufferedImage gray) {
        int w=gray.getWidth(),h=gray.getHeight();long[][] ig=new long[h][w];
        for(int y=0;y<h;y++){long rs=0;for(int x=0;x<w;x++){rs+=getGray(gray,x,y);ig[y][x]=rs+(y>0?ig[y-1][x]:0);}}
        return ig;
    }

    private long integralSum(long[][] ig, int x1, int y1, int x2, int y2) {
        return ig[y2][x2]-(x1>0?ig[y2][x1-1]:0)-(y1>0?ig[y1-1][x2]:0)+(x1>0&&y1>0?ig[y1-1][x1-1]:0);
    }

    // ======================== Perspective Transform ========================

    public BufferedImage perspectiveTransform(BufferedImage src, int[][] corners) {
        double wTop=dist(corners[0],corners[1]),wBot=dist(corners[3],corners[2]);
        double hLeft=dist(corners[0],corners[3]),hRight=dist(corners[1],corners[2]);
        int outW=(int)Math.max(wTop,wBot),outH=(int)Math.max(hLeft,hRight);
        int maxOut=2000;if(outW>maxOut||outH>maxOut){double s=Math.min((double)maxOut/outW,(double)maxOut/outH);outW=(int)(outW*s);outH=(int)(outH*s);}
        outW=Math.max(10,outW);outH=Math.max(10,outH);

        double x0=corners[0][0],y0=corners[0][1],x1=corners[1][0],y1=corners[1][1];
        double x2=corners[2][0],y2=corners[2][1],x3=corners[3][0],y3=corners[3][1];
        double[][]A={{0,0,1,0,0,0,0,0},{0,0,0,0,0,1,0,0},{outW,0,1,0,0,0,-outW*x1,0},{0,0,0,outW,0,1,-outW*y1,0},{outW,outH,1,0,0,0,-outW*x2,-outH*x2},{0,0,0,outW,outH,1,-outW*y2,-outH*y2},{0,outH,1,0,0,0,0,-outH*x3},{0,0,0,0,outH,1,0,-outH*y3}};
        double[]B={x0,y0,x1,y1,x2,y2,x3,y3};
        double[]p=solveLinear8(A,B);
        if(p==null){log.warn("Perspective solve failed");return src.getSubimage(Math.max(0,corners[0][0]),Math.max(0,corners[0][1]),Math.min(src.getWidth()-corners[0][0],outW),Math.min(src.getHeight()-corners[0][1],outH));}

        double a=p[0],b=p[1],c=p[2],d=p[3],e=p[4],f=p[5],g=p[6],h=p[7];
        int sw=src.getWidth(),sh=src.getHeight();BufferedImage out=new BufferedImage(outW,outH,src.getType());
        for(int vy=0;vy<outH;vy++)for(int vx=0;vx<outW;vx++){
            double denom=g*vx+h*vy+1;double srcX=(a*vx+b*vy+c)/denom;double srcY=(d*vx+e*vy+f)/denom;
            int x0i=clamp((int)Math.floor(srcX),0,sw-1),y0i=clamp((int)Math.floor(srcY),0,sh-1);
            int x1i=clamp(x0i+1,0,sw-1),y1i=clamp(y0i+1,0,sh-1);
            double fx=srcX-Math.floor(srcX),fy=srcY-Math.floor(srcY);
            int c00=src.getRGB(x0i,y0i),c10=src.getRGB(x1i,y0i),c01=src.getRGB(x0i,y1i),c11=src.getRGB(x1i,y1i);
            out.setRGB(vx,vy,bilinearInterp(c00,c10,c01,c11,fx,fy));
        }
        return out;
    }

    private int bilinearInterp(int c00,int c10,int c01,int c11,double fx,double fy){
        int r=(int)(bilerp((c00>>16)&0xFF,(c10>>16)&0xFF,(c01>>16)&0xFF,(c11>>16)&0xFF,fx,fy));
        int gr=(int)(bilerp((c00>>8)&0xFF,(c10>>8)&0xFF,(c01>>8)&0xFF,(c11>>8)&0xFF,fx,fy));
        int bl=(int)(bilerp(c00&0xFF,c10&0xFF,c01&0xFF,c11&0xFF,fx,fy));
        return(clamp(r,0,255)<<16)|(clamp(gr,0,255)<<8)|clamp(bl,0,255);
    }

    private double bilerp(double v00,double v10,double v01,double v11,double fx,double fy){
        return v00*(1-fx)*(1-fy)+v10*fx*(1-fy)+v01*(1-fx)*fy+v11*fx*fy;
    }

    private double[] solveLinear8(double[][]A,double[]B){
        int n=8;double[][]mat=new double[n][n+1];
        for(int i=0;i<n;i++){System.arraycopy(A[i],0,mat[i],0,n);mat[i][n]=B[i];}
        for(int col=0;col<n;col++){
            int mr=col;
            for(int row=col+1;row<n;row++) if(Math.abs(mat[row][col])>Math.abs(mat[mr][col])) mr=row;
            double[]tmp=mat[col];mat[col]=mat[mr];mat[mr]=tmp;
            if(Math.abs(mat[col][col])<1e-10) return null;
            for(int row=col+1;row<n;row++){double f=mat[row][col]/mat[col][col];for(int j=col;j<=n;j++)mat[row][j]-=f*mat[col][j];}
        }
        double[]x=new double[n];
        for(int i=n-1;i>=0;i--){x[i]=mat[i][n];for(int j=i+1;j<n;j++)x[i]-=mat[i][j]*x[j];x[i]/=mat[i][i];}
        return x;
    }

    // ======================== Scan Enhancement ========================

    /**
     * CamScanner-style enhancement.
     * Uses background normalization + adaptive threshold blend for crisp text.
     */
    public BufferedImage enhanceScan(BufferedImage img) {
        BufferedImage gray = toGrayscale(img);

        // Step 1: Background normalization — the KEY to CamScanner look
        // Divides by blurred version to remove shadows, then scales to 0-255
        BufferedImage normalized = normalizeLighting(gray, 51);

        // Step 2: Contrast stretch — push to full range
        BufferedImage contrast = stretchContrast(normalized, 1, 99);

        // Step 3: Adaptive threshold — makes text crisp black
        BufferedImage binary = sauvolaThreshold(contrast, 25, 0.2);

        // Step 4: Blend binary (for text sharpness) with contrast (for gray levels)
        // Text areas use binary (pure black), non-text areas use contrast (gray levels)
        BufferedImage blended = blendBinaryWithGray(contrast, binary);

        // Step 5: White push — clean background
        BufferedImage white = pushWhite(blended, 220);

        // Step 6: Sharpen for crisp edges
        return sharpen(white);
    }

    /**
     * Blend binary (Sauvola) result with grayscale result.
     * Text areas (Sauvola dark) → push to pure black.
     * Non-text areas (Sauvola light) → keep original gray (preserves fingerprints, stamps, photos).
     */
    private BufferedImage blendBinaryWithGray(BufferedImage gray, BufferedImage binary) {
        int w = gray.getWidth(), h = gray.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int g = getGray(gray, x, y);
                int b = getGray(binary, x, y);
                if (b < 128) {
                    // Text pixel — darken it
                    setGray(out, x, y, clamp((int) (g * 0.3), 0, 255));
                } else {
                    // Non-text pixel — keep original gray value (don't lighten)
                    setGray(out, x, y, g);
                }
            }
        }
        return out;
    }

    /**
     * Background normalization: divide by heavily-blurred version.
     * result = gray / blurred * 255
     */
    private BufferedImage normalizeLighting(BufferedImage gray, int blurSize) {
        int w = gray.getWidth(), h = gray.getHeight();
        int sw = Math.max(1, w/4), sh = Math.max(1, h/4);
        BufferedImage small = resize(gray, sw, sh);
        BufferedImage smallBlurred = gaussianBlur(small, Math.max(3, blurSize/4));
        BufferedImage blurred = resize(smallBlurred, w, h);

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int g = getGray(gray, x, y);
                int b = Math.max(1, getGray(blurred, x, y));
                int val = (int)((double)g / b * 255);
                setGray(out, x, y, clamp(val, 0, 255));
            }
        }
        return out;
    }

    /**
     * Bilateral filter — edge-preserving denoise.
     * Smooths flat areas but keeps edges sharp. Perfect for document scanning.
     * d: kernel diameter, sigmaColor: color range sigma, sigmaSpace: spatial sigma
     */
    private BufferedImage bilateralFilter(BufferedImage gray, int d, double sigmaColor, double sigmaSpace) {
        int w = gray.getWidth(), h = gray.getHeight();
        int half = d / 2;
        double sc2 = 2 * sigmaColor * sigmaColor;
        double ss2 = 2 * sigmaSpace * sigmaSpace;

        // Pre-compute spatial Gaussian weights
        double[] spatialW = new double[d * d];
        for (int ky = 0; ky < d; ky++)
            for (int kx = 0; kx < d; kx++) {
                double dy2 = (ky - half) * (ky - half) + (kx - half) * (kx - half);
                spatialW[ky * d + kx] = Math.exp(-dy2 / ss2);
            }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int center = getGray(gray, x, y);
                double sumW = 0, sumV = 0;
                for (int ky = 0; ky < d; ky++) {
                    for (int kx = 0; kx < d; kx++) {
                        int px = clamp(x + kx - half, 0, w - 1);
                        int py = clamp(y + ky - half, 0, h - 1);
                        int neighbor = getGray(gray, px, py);
                        double colorDiff = (neighbor - center) * (neighbor - center);
                        double sw = spatialW[ky * d + kx] * Math.exp(-colorDiff / sc2);
                        sumW += sw;
                        sumV += sw * neighbor;
                    }
                }
                setGray(out, x, y, clamp((int) (sumV / sumW), 0, 255));
            }
        }
        return out;
    }

    /**
     * Unsharp mask — much sharper text than simple Laplacian sharpening.
     * sharpened = original + amount * (original - blurred)
     * radius: blur radius for the mask, amount: sharpness strength (1.0-2.0)
     */
    private BufferedImage unsharpMask(BufferedImage gray, int radius, double amount) {
        int w = gray.getWidth(), h = gray.getHeight();
        BufferedImage blurred = gaussianBlur(gray, radius * 2 + 1);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int orig = getGray(gray, x, y);
                int blur = getGray(blurred, x, y);
                int val = (int) (orig + amount * (orig - blur));
                setGray(out, x, y, clamp(val, 0, 255));
            }
        }
        return out;
    }

    /**
     * Percentile-based contrast stretch.
     */
    private BufferedImage stretchContrast(BufferedImage gray, int lowPct, int highPct) {
        int w=gray.getWidth(),h=gray.getHeight();int total=w*h;
        int[] hist=new int[256];for(int y=0;y<h;y++)for(int x=0;x<w;x++)hist[getGray(gray,x,y)]++;
        int lowCount=total*lowPct/100,highCount=total*highPct/100;int min=0,max=255,cumulative=0;
        for(int i=0;i<256;i++){cumulative+=hist[i];if(cumulative>=lowCount&&min==0)min=i;if(cumulative>=highCount){max=i;break;}}
        if(max-min<20){min=0;max=255;}
        BufferedImage out=new BufferedImage(w,h,BufferedImage.TYPE_BYTE_GRAY);double range=max-min;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int v=(int)((getGray(gray,x,y)-min)/range*255);setGray(out,x,y,clamp(v,0,255));}
        return out;
    }

    /**
     * Gamma correction: pixel = 255 * (pixel/255)^gamma
     * gamma < 1 darkens midtones (text stays dark), gamma > 1 brightens
     */
    private BufferedImage applyGamma(BufferedImage gray, double gamma) {
        int w=gray.getWidth(),h=gray.getHeight();int[] lut=new int[256];
        for(int i=0;i<256;i++){lut[i]=clamp((int)(255*Math.pow(i/255.0,gamma)),0,255);}
        BufferedImage out=new BufferedImage(w,h,BufferedImage.TYPE_BYTE_GRAY);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)setGray(out,x,y,lut[getGray(gray,x,y)]);
        return out;
    }

    /**
     * S-curve: smoothstep 3t^2 - 2t^3
     */
    private BufferedImage applyCurve(BufferedImage gray) {
        int w=gray.getWidth(),h=gray.getHeight();int[] lut=new int[256];
        for(int i=0;i<256;i++){double t=i/255.0;double s=t*t*(3-2*t);lut[i]=clamp((int)(s*255),0,255);}
        BufferedImage out=new BufferedImage(w,h,BufferedImage.TYPE_BYTE_GRAY);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)setGray(out,x,y,lut[getGray(gray,x,y)]);
        return out;
    }

    /**
     * Push pixels above threshold to pure white.
     */
    private BufferedImage pushWhite(BufferedImage gray, int threshold) {
        int w=gray.getWidth(),h=gray.getHeight();BufferedImage out=new BufferedImage(w,h,BufferedImage.TYPE_BYTE_GRAY);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int v=getGray(gray,x,y);setGray(out,x,y,v>=threshold?255:v);}
        return out;
    }

    /**
     * Sauvola adaptive threshold.
     * T(x,y) = mean * (1 + k * (std/R - 1))
     * Where R=128 (dynamic range), k=0.15 (sensitivity).
     * Produces clean black/white output like CamScanner.
     */
    private BufferedImage sauvolaThreshold(BufferedImage gray, int windowSize, double k) {
        int w = gray.getWidth(), h = gray.getHeight();
        int half = windowSize / 2;
        double R = 128.0;

        // Build integral images for fast mean and variance computation
        long[][] integral = new long[h][w];
        long[][] integralSq = new long[h][w];
        for (int y = 0; y < h; y++) {
            long rowSum = 0, rowSumSq = 0;
            for (int x = 0; x < w; x++) {
                int v = getGray(gray, x, y);
                rowSum += v;
                rowSumSq += (long) v * v;
                integral[y][x] = rowSum + (y > 0 ? integral[y - 1][x] : 0);
                integralSq[y][x] = rowSumSq + (y > 0 ? integralSq[y - 1][x] : 0);
            }
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int x1 = Math.max(0, x - half), y1 = Math.max(0, y - half);
                int x2 = Math.min(w - 1, x + half), y2 = Math.min(h - 1, y + half);
                int count = (x2 - x1 + 1) * (y2 - y1 + 1);

                long sum = integral[y2][x2]
                        - (x1 > 0 ? integral[y2][x1 - 1] : 0)
                        - (y1 > 0 ? integral[y1 - 1][x2] : 0)
                        + (x1 > 0 && y1 > 0 ? integral[y1 - 1][x1 - 1] : 0);
                long sumSq = integralSq[y2][x2]
                        - (x1 > 0 ? integralSq[y2][x1 - 1] : 0)
                        - (y1 > 0 ? integralSq[y1 - 1][x2] : 0)
                        + (x1 > 0 && y1 > 0 ? integralSq[y1 - 1][x1 - 1] : 0);

                double mean = (double) sum / count;
                double variance = (double) sumSq / count - mean * mean;
                double std = Math.sqrt(Math.max(0, variance));

                double threshold = mean * (1.0 + k * (std / R - 1.0));
                int pixel = getGray(gray, x, y);
                setGray(out, x, y, pixel > threshold ? 255 : 0);
            }
        }
        return out;
    }

    /**
     * Sharpen using unsharp mask kernel.
     */
    public BufferedImage sharpen(BufferedImage image) {
        int[][] kernel = {{0, -1, 0}, {-1, 5, -1}, {0, -1, 0}};
        int w = image.getWidth(), h = image.getHeight();
        BufferedImage out = new BufferedImage(w, h, image.getType());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (image.getType() == BufferedImage.TYPE_BYTE_GRAY || image.getType() == BufferedImage.TYPE_BYTE_BINARY) {
                    int sum = 0;
                    for (int ky = -1; ky <= 1; ky++)
                        for (int kx = -1; kx <= 1; kx++)
                            sum += getGray(image, clamp(x + kx, 0, w - 1), clamp(y + ky, 0, h - 1)) * kernel[ky + 1][kx + 1];
                    setGray(out, x, y, clamp(sum, 0, 255));
                } else {
                    int r = 0, g = 0, b = 0;
                    for (int ky = -1; ky <= 1; ky++)
                        for (int kx = -1; kx <= 1; kx++) {
                            int rgb = image.getRGB(clamp(x + kx, 0, w - 1), clamp(y + ky, 0, h - 1));
                            int k = kernel[ky + 1][kx + 1];
                            r += ((rgb >> 16) & 0xFF) * k; g += ((rgb >> 8) & 0xFF) * k; b += (rgb & 0xFF) * k;
                        }
                    out.setRGB(x, y, (clamp(r, 0, 255) << 16) | (clamp(g, 0, 255) << 8) | clamp(b, 0, 255));
                }
            }
        }
        return out;
    }

    // ======================== Helpers ========================

    private int getGray(BufferedImage img, int x, int y) { return img.getRGB(x, y) & 0xFF; }
    private void setGray(BufferedImage img, int x, int y, int val) { val = clamp(val, 0, 255); img.setRGB(x, y, (val << 16) | (val << 8) | val); }
    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private double dist(int[] a, int[] b) { return Math.sqrt(sq(b[0] - a[0]) + sq(b[1] - a[1])); }
    private double sq(double v) { return v * v; }
}
