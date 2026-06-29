package com.codexrisk.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

final class WidgetBackground {
    private WidgetBackground() {
    }

    static Bitmap create(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.max(480, (int) (360 * density));
        int height = Math.max(200, (int) (150 * density));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        drawBase(canvas, width, height);
        drawGrid(canvas, width, height, density);
        drawAccentBar(canvas, width, height, density);
        drawSignalWave(canvas, width, height, density);
        drawReadabilityOverlay(canvas, width, height, density);
        drawBorder(canvas, width, height, density);
        return bitmap;
    }

    private static void drawBase(Canvas canvas, int width, int height) {
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setShader(new LinearGradient(
                0,
                0,
                width,
                height,
                new int[] {
                        Color.rgb(14, 16, 20),
                        Color.rgb(24, 18, 16),
                        Color.rgb(12, 13, 17)
                },
                new float[] {0f, 0.42f, 1f},
                Shader.TileMode.CLAMP
        ));
        canvas.drawRect(0, 0, width, height, fill);
    }

    private static void drawGrid(Canvas canvas, int width, int height, float density) {
        Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        grid.setColor(Color.argb(22, 232, 196, 92));
        grid.setStrokeWidth(Math.max(1f, density * 0.6f));
        float step = 34f * density;
        for (float x = 0; x < width; x += step) {
            canvas.drawLine(x, 0, x, height, grid);
        }
        for (float y = 0; y < height; y += step) {
            canvas.drawLine(0, y, width, y, grid);
        }
    }

    private static void drawAccentBar(Canvas canvas, int width, int height, float density) {
        Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
        accent.setShader(new LinearGradient(
                0,
                0,
                0,
                height,
                new int[] {
                        Color.argb(255, 250, 215, 74),
                        Color.argb(245, 246, 196, 54),
                        Color.argb(180, 140, 94, 18)
                },
                new float[] {0f, 0.55f, 1f},
                Shader.TileMode.CLAMP
        ));
        canvas.drawRoundRect(new RectF(0, 0, 6 * density, height), 4 * density, 4 * density, accent);
    }

    private static void drawSignalWave(Canvas canvas, int width, int height, float density) {
        Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
        wave.setStyle(Paint.Style.STROKE);
        wave.setStrokeCap(Paint.Cap.ROUND);
        wave.setStrokeWidth(3f * density);
        wave.setShader(new LinearGradient(
                0,
                0,
                width,
                0,
                new int[] {
                        Color.argb(220, 198, 171, 42),
                        Color.argb(255, 245, 225, 74),
                        Color.argb(160, 231, 93, 86)
                },
                new float[] {0f, 0.62f, 1f},
                Shader.TileMode.CLAMP
        ));

        Path path = new Path();
        float startX = 34f * density;
        float startY = height * 0.42f;
        path.moveTo(startX, startY);
        path.cubicTo(width * 0.28f, height * 0.40f, width * 0.44f, height * 0.46f, width * 0.58f, height * 0.38f);
        path.cubicTo(width * 0.72f, height * 0.32f, width * 0.83f, height * 0.52f, width - 36f * density, height * 0.36f);
        canvas.drawPath(path, wave);

        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setShader(new LinearGradient(
                width * 0.72f,
                height * 0.18f,
                width * 0.95f,
                height * 0.78f,
                new int[] {
                        Color.argb(0, 227, 93, 91),
                        Color.argb(88, 227, 93, 91),
                        Color.argb(0, 227, 93, 91)
                },
                new float[] {0f, 0.55f, 1f},
                Shader.TileMode.CLAMP
        ));
        canvas.drawOval(new RectF(width * 0.72f, height * 0.12f, width * 1.02f, height * 0.88f), glow);
    }

    private static void drawReadabilityOverlay(Canvas canvas, int width, int height, float density) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.argb(108, 0, 0, 0));
        canvas.drawRoundRect(new RectF(0, 0, width, height), 20 * density, 20 * density, paint);

        Paint textSafe = new Paint(Paint.ANTI_ALIAS_FLAG);
        textSafe.setShader(new LinearGradient(
                0,
                0,
                width,
                0,
                new int[] {Color.argb(115, 0, 0, 0), Color.argb(82, 0, 0, 0), Color.argb(40, 0, 0, 0)},
                new float[] {0f, 0.56f, 1f},
                Shader.TileMode.CLAMP
        ));
        canvas.drawRoundRect(new RectF(0, 0, width, height), 20 * density, 20 * density, textSafe);

        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setShader(new LinearGradient(
                0,
                height * 0.15f,
                0,
                height,
                Color.argb(0, 0, 0, 0),
                Color.argb(78, 0, 0, 0),
                Shader.TileMode.CLAMP
        ));
        canvas.drawRoundRect(new RectF(0, 0, width, height), 20 * density, 20 * density, glow);
    }

    private static void drawBorder(Canvas canvas, int width, int height, float density) {
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1, density));
        stroke.setColor(Color.argb(74, 232, 196, 92));
        float inset = stroke.getStrokeWidth() / 2f;
        canvas.drawRoundRect(new RectF(inset, inset, width - inset, height - inset), 20 * density, 20 * density, stroke);
    }
}
