package com.davemorrissey.labs.subscaleview;

import java.util.Arrays;

/** Host-JVM fixture that loads and calls the production JNI implementation. */
public final class CropBorders {
    static {
        System.loadLibrary("ssiv_crop");
    }

    private CropBorders() {}

    public static native int[] findCropBorders(byte[] rgba, int width, int height);

    public static native int[] findCropBordersGray(byte[] gray, int width, int height);

    public static void main(String[] args) {
        int width = 80;
        int height = 60;

        byte[] whiteFrame = solidGray(width, height, 255);
        fillGrayRect(whiteFrame, width, 10, 8, 70, 52, 0);
        assertEquivalent("white border", width, height, rgbaFromGray(whiteFrame), whiteFrame,
                new int[] {10, 8, 60, 44});

        byte[] blackFrame = solidGray(width, height, 0);
        fillGrayRect(blackFrame, width, 10, 8, 70, 52, 255);
        assertEquivalent("black border", width, height, rgbaFromGray(blackFrame), blackFrame,
                new int[] {10, 8, 60, 44});

        byte[] coloredRgba = coloredContentOnWhite(width, height);
        byte[] coloredGray = grayFromRgba(coloredRgba);
        assertEquivalent("mixed-color content", width, height, coloredRgba, coloredGray,
                new int[] {10, 8, 60, 44});

        byte[] mixedBorder = solidGray(width, height, 128);
        for (int x = 0; x < width; x++) {
            mixedBorder[x] = (byte) (x < width / 2 ? 0 : 255);
            mixedBorder[(height - 1) * width + x] = (byte) (x < width / 2 ? 0 : 255);
        }
        for (int y = 0; y < height; y++) {
            mixedBorder[y * width] = (byte) (y < height / 2 ? 0 : 255);
            mixedBorder[y * width + width - 1] = (byte) (y < height / 2 ? 0 : 255);
        }
        assertEquivalent("mixed black/white border", width, height,
                rgbaFromGray(mixedBorder), mixedBorder, new int[] {0, 0, width, height});

        byte[] blankWhite = solidGray(width, height, 255);
        assertEquivalent("blank white", width, height, rgbaFromGray(blankWhite), blankWhite,
                new int[] {0, 0, width, height});

        byte[] noBorder = solidGray(width, height, 200);
        assertEquivalent("uniform image with no border", width, height,
                rgbaFromGray(noBorder), noBorder, new int[] {0, 0, width, height});

        expectIllegalArgument("zero width", () -> findCropBordersGray(new byte[0], 0, 1));
        expectIllegalArgument("negative height", () -> findCropBordersGray(new byte[0], 1, -1));
        expectIllegalArgument("pixel count exceeds JNI array limit",
                () -> findCropBordersGray(new byte[0], Integer.MAX_VALUE, Integer.MAX_VALUE));
        expectIllegalArgument("grayscale length mismatch",
                () -> findCropBordersGray(new byte[1], 2, 1));
        expectIllegalArgument("null grayscale array", () -> findCropBordersGray(null, 1, 1));

        System.out.println("Native crop JNI smoke passed (6 image cases, 5 invalid-input cases).");
    }

    private static void assertEquivalent(String name, int width, int height,
                                         byte[] rgba, byte[] gray, int[] expected) {
        int[] rgbaBounds = findCropBorders(rgba, width, height);
        int[] grayBounds = findCropBordersGray(gray, width, height);
        if (!Arrays.equals(rgbaBounds, grayBounds)) {
            throw new AssertionError(name + " RGBA/gray mismatch: "
                    + Arrays.toString(rgbaBounds) + " vs " + Arrays.toString(grayBounds));
        }
        if (!Arrays.equals(expected, grayBounds)) {
            throw new AssertionError(name + " unexpected bounds: "
                    + Arrays.toString(grayBounds) + ", expected " + Arrays.toString(expected));
        }
        System.out.println("PASS " + name + " " + Arrays.toString(grayBounds));
    }

    private static void expectIllegalArgument(String name, Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            System.out.println("PASS " + name + " -> IllegalArgumentException");
            return;
        }
        throw new AssertionError(name + " did not throw IllegalArgumentException");
    }

    private static byte[] solidGray(int width, int height, int value) {
        byte[] pixels = new byte[width * height];
        Arrays.fill(pixels, (byte) value);
        return pixels;
    }

    private static void fillGrayRect(byte[] pixels, int width, int left, int top,
                                     int right, int bottom, int value) {
        for (int y = top; y < bottom; y++) {
            Arrays.fill(pixels, y * width + left, y * width + right, (byte) value);
        }
    }

    private static byte[] rgbaFromGray(byte[] gray) {
        byte[] rgba = new byte[gray.length * 4];
        for (int i = 0; i < gray.length; i++) {
            byte value = gray[i];
            rgba[i * 4] = value;
            rgba[i * 4 + 1] = value;
            rgba[i * 4 + 2] = value;
            rgba[i * 4 + 3] = (byte) 255;
        }
        return rgba;
    }

    private static byte[] coloredContentOnWhite(int width, int height) {
        byte[] rgba = new byte[width * height * 4];
        for (int i = 0; i < width * height; i++) {
            rgba[i * 4] = (byte) 255;
            rgba[i * 4 + 1] = (byte) 255;
            rgba[i * 4 + 2] = (byte) 255;
            rgba[i * 4 + 3] = (byte) 255;
        }
        int[][] colors = {
                {255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {255, 0, 255},
        };
        for (int y = 8; y < 52; y++) {
            for (int x = 10; x < 70; x++) {
                int pixel = (y * width + x) * 4;
                int[] color = colors[((x / 5) + (y / 5)) % colors.length];
                rgba[pixel] = (byte) color[0];
                rgba[pixel + 1] = (byte) color[1];
                rgba[pixel + 2] = (byte) color[2];
            }
        }
        return rgba;
    }

    private static byte[] grayFromRgba(byte[] rgba) {
        byte[] gray = new byte[rgba.length / 4];
        for (int i = 0; i < gray.length; i++) {
            int red = rgba[i * 4] & 0xff;
            int green = rgba[i * 4 + 1] & 0xff;
            int blue = rgba[i * 4 + 2] & 0xff;
            gray[i] = (byte) ((red * 77 + green * 150 + blue * 29) >> 8);
        }
        return gray;
    }
}
