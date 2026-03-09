package com.nfcdocumentreader;

import android.graphics.Bitmap;
import android.util.Log;

import jj2000.j2k.codestream.HeaderInfo;
import jj2000.j2k.codestream.reader.BitstreamReaderAgent;
import jj2000.j2k.codestream.reader.HeaderDecoder;
import jj2000.j2k.image.BlkImgDataSrc;
import jj2000.j2k.image.Coord;
import jj2000.j2k.image.DataBlkInt;
import jj2000.j2k.image.ImgDataConverter;
import jj2000.j2k.image.invcomptransf.InvCompTransf;
import jj2000.j2k.io.BEBufferedRandomAccessFile;
import jj2000.j2k.io.RandomAccessIO;
import jj2000.j2k.util.ParameterList;
import jj2000.j2k.wavelet.synthesis.InverseWT;

import java.io.File;

/**
 * JPEG2000 decoder for Android using the jj2000 library.
 * Uses file-based I/O for maximum compatibility.
 * Ported from Kotlin Jpeg2000Decoder.kt.
 */
public class Jpeg2000Decoder {

    private static final String TAG = "Jpeg2000Decoder";

    private static final String[][] DEFAULT_PARAMS = {
        {"u", "off"},
        {"v", "off"},
        {"debug", "off"},
        {"verbose", "off"},
        {"pfile", ""},
        {"res", "-1"},
        {"i", ""},
        {"o", ""},
        {"rate", "-1"},
        {"nbytes", "-1"},
        {"parsing", "on"},
        {"ncb_quit", "-1"},
        {"l_quit", "-1"},
        {"m_quit", "-1"},
        {"poc_quit", "off"},
        {"one_tp", "off"},
        {"comp_transf", "on"},
        {"cdstr_info", "off"},
        {"nocolorspace", "off"},
        {"colorspace_debug", "off"},
        {"Cer", "on"},
        {"Cverber", "off"},
        {"Rno_roi", ""},
        {"IcolorSpacedebug", "off"}
    };

    /**
     * Decode JPEG2000 image bytes into an Android Bitmap.
     */
    public static Bitmap decode(byte[] imageBytes) {
        File tempFile = null;
        RandomAccessIO rio = null;

        try {
            // Write to temp file — jj2000 works more reliably with file-based I/O
            tempFile = File.createTempFile("jp2_dec_", ".jp2");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile);
            fos.write(imageBytes);
            fos.close();
            Log.d(TAG, "JP2 data: " + imageBytes.length + " bytes, wrote to " + tempFile.getAbsolutePath());

            rio = new BEBufferedRandomAccessFile(tempFile, "r");

            // Set up decoder parameters
            ParameterList defPl = new ParameterList();
            for (String[] param : DEFAULT_PARAMS) {
                defPl.setProperty(param[0], param[1]);
            }
            ParameterList pl = new ParameterList(defPl);

            // Check for JP2 file format container vs raw J2K codestream
            if (isJP2Container(imageBytes)) {
                Log.d(TAG, "Detected JP2 container format");
                // Find the J2K codestream start (0xFF 0x4F) within the JP2 container
                int csPos = findCodestreamPosition(imageBytes);
                if (csPos > 0) {
                    rio.seek(csPos);
                    Log.d(TAG, "Codestream starts at offset " + csPos);
                }
            } else {
                Log.d(TAG, "Detected raw J2K codestream");
            }

            // Parse the codestream header
            HeaderInfo headerInfo = new HeaderInfo();
            HeaderDecoder hd = new HeaderDecoder(rio, pl, headerInfo);
            int numComps = hd.getNumComps();
            jj2000.j2k.decoder.DecoderSpecs decoderSpecs = hd.getDecoderSpecs();
            int imgW = hd.getImgWidth();
            int imgH = hd.getImgHeight();

            Log.d(TAG, "JP2 header: " + imgW + "x" + imgH + ", " + numComps + " components");

            if (imgW <= 0 || imgH <= 0 || imgW > 8192 || imgH > 8192) {
                Log.e(TAG, "Invalid JP2 dimensions: " + imgW + "x" + imgH);
                return null;
            }

            // Original bit depths per component
            int[] depths = new int[numComps];
            for (int i = 0; i < numComps; i++) {
                depths[i] = hd.getOriginalBitDepth(i);
            }

            // Resolve the "res" parameter: -1 means max resolution
            int resolvedRes = pl.getIntParameter("res");
            if (resolvedRes < 0) {
                resolvedRes = decoderSpecs.dls.getMin();
                pl.setProperty("res", String.valueOf(resolvedRes));
                Log.d(TAG, "Resolved res to " + resolvedRes + " (max decomposition levels)");
            }

            // Build full decoding pipeline
            BitstreamReaderAgent bra = BitstreamReaderAgent.createInstance(rio, hd, pl, decoderSpecs, false, headerInfo);
            jj2000.j2k.entropy.decoder.EntropyDecoder entDec = hd.createEntropyDecoder(bra, pl);
            jj2000.j2k.roi.ROIDeScaler roi = hd.createROIDeScaler(entDec, pl, decoderSpecs);
            jj2000.j2k.quantization.dequantizer.Dequantizer deq = hd.createDequantizer(roi, depths, decoderSpecs);
            InverseWT invWT = InverseWT.createInstance(deq, decoderSpecs);
            // Set full resolution level — without this, InverseWT defaults to level 0 (lowest)
            invWT.setImgResLevel(resolvedRes);
            ImgDataConverter converter = new ImgDataConverter(invWT, 0);
            int[] compIdx = new int[numComps];
            for (int i = 0; i < numComps; i++) compIdx[i] = i;
            InvCompTransf ict = new InvCompTransf(converter, decoderSpecs, compIdx, pl);

            int width = ict.getImgWidth();
            int height = ict.getImgHeight();
            Log.d(TAG, "Decoding " + width + "x" + height + " pixels...");

            // Read pixel data
            Bitmap bitmap = readPixels(ict, width, height, numComps);
            Log.d(TAG, "JP2 decode success: " + (bitmap != null ? bitmap.getWidth() + "x" + bitmap.getHeight() : "null"));
            return bitmap;

        } catch (Throwable e) {
            // Catch Throwable because jj2000 can throw Error types on Android
            Log.e(TAG, "JP2 decode failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            return null;
        } finally {
            if (rio != null) {
                try { rio.close(); } catch (Exception ignored) {}
            }
            if (tempFile != null) {
                try { tempFile.delete(); } catch (Exception ignored) {}
            }
        }
    }

    private static Bitmap readPixels(BlkImgDataSrc src, int imgW, int imgH, int numComps) {
        int n = Math.min(numComps, 3);
        int[] pixels = new int[imgW * imgH];

        Coord nTiles = src.getNumTiles(new Coord());
        int ntX = nTiles.x;
        int ntY = nTiles.y;
        Log.d(TAG, "Tiles: " + ntX + "x" + ntY);

        for (int t = 0; t < ntX * ntY; t++) {
            int tx = t % ntX;
            int ty = t / ntX;
            src.setTile(tx, ty);

            int tileW = src.getTileWidth();
            int tileH = src.getTileHeight();
            int tileULX = src.getTilePartULX() - src.getImgULX();
            int tileULY = src.getTilePartULY() - src.getImgULY();

            // Read each component for this tile
            DataBlkInt[] comp = new DataBlkInt[n];
            for (int c = 0; c < n; c++) {
                DataBlkInt blk = new DataBlkInt(0, 0, tileW, tileH);
                comp[c] = (DataBlkInt) src.getInternCompData(blk, c);
            }

            // Composite tile pixels into the full image
            for (int y = 0; y < tileH; y++) {
                for (int x = 0; x < tileW; x++) {
                    int imgX = tileULX + x;
                    int imgY = tileULY + y;
                    if (imgX < 0 || imgX >= imgW || imgY < 0 || imgY >= imgH) continue;

                    int pi = imgY * imgW + imgX;

                    if (n >= 3) {
                        int r = clamp(getPixelVal(comp[0], x, y), 0, 255);
                        int g = clamp(getPixelVal(comp[1], x, y), 0, 255);
                        int b = clamp(getPixelVal(comp[2], x, y), 0, 255);
                        pixels[pi] = (0xFF << 24) | (r << 16) | (g << 8) | b;
                    } else {
                        int v = clamp(getPixelVal(comp[0], x, y), 0, 255);
                        pixels[pi] = (0xFF << 24) | (v << 16) | (v << 8) | v;
                    }
                }
            }
        }

        return Bitmap.createBitmap(pixels, imgW, imgH, Bitmap.Config.ARGB_8888);
    }

    private static int getPixelVal(DataBlkInt blk, int x, int y) {
        int idx = blk.offset + y * blk.scanw + x;
        if (idx >= 0 && idx < blk.getDataInt().length) {
            return blk.getDataInt()[idx];
        }
        return 0;
    }

    /**
     * Find the J2K codestream start position (0xFF 0x4F marker) within a JP2 container.
     * This avoids depending on FileFormatReader field access which varies across jj2000 versions.
     */
    private static int findCodestreamPosition(byte[] data) {
        for (int i = 0; i < data.length - 1; i++) {
            if (data[i] == (byte) 0xFF && data[i + 1] == (byte) 0x4F) {
                return i;
            }
        }
        return 0;
    }

    private static boolean isJP2Container(byte[] data) {
        return data.length > 12 &&
            data[0] == (byte) 0x00 && data[1] == (byte) 0x00 &&
            data[2] == (byte) 0x00 && data[3] == (byte) 0x0C &&
            data[4] == (byte) 0x6A && data[5] == (byte) 0x50;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
