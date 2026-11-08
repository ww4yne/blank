package app.moting;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.SeekBar;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.AppCompatButton;

final class InkUi {
    private InkUi() {}

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
    static TextView text(Context context, String value, float size, boolean bold) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextColor(Color.BLACK);
        text.setTextSize(size);
        if (bold) text.setTypeface(null, android.graphics.Typeface.BOLD);
        text.setIncludeFontPadding(false);
        text.setGravity(Gravity.CENTER_VERTICAL);
        return text;
    }
    static GradientDrawable border(Context context, boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(selected ? Color.BLACK : Color.WHITE);
        drawable.setStroke(dp(context, 1), Color.BLACK);
        drawable.setCornerRadius(0);
        return drawable;
    }
    static Button button(Context context, String value, boolean selected, Runnable action) {
        Button button = new AppCompatButton(context);
        button.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        styleButton(button, selected, action);
        button.setText(value);
        return button;
    }
    static Drawable underline(Context context, boolean selected) {
        if (!selected) return new ColorDrawable(Color.WHITE);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{
                new ColorDrawable(Color.WHITE), new ColorDrawable(Color.BLACK)});
        layers.setLayerHeight(1, dp(context, 2));
        layers.setLayerGravity(1, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        return layers;
    }
    private static void styleButton(Button button, boolean selected, Runnable action) {
        Context context = button.getContext();
        boolean primary = button instanceof IconButton && selected;
        button.setAllCaps(false);
        button.setTextColor(primary ? Color.WHITE : Color.BLACK);
        button.setSelected(selected);
        button.setTextSize(14);
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setMinWidth(dp(context, 48));
        button.setMinimumWidth(dp(context, 48));
        button.setIncludeFontPadding(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(context, 6), dp(context, 5), dp(context, 6), dp(context, 5));
        if (primary) {
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(Color.BLACK);
            button.setBackground(circle);
        } else button.setBackground(underline(context, selected));
        button.setBackgroundTintList(null);
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setTranslationZ(0);
        button.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
        button.setForeground(null);
        button.setOnClickListener(view -> action.run());
    }
    static final class IconButton extends AppCompatButton {
        private Drawable icon;
        private int resource;
        private String label = "";
        IconButton(Context context) { super(context); }
        IconButton(Context context, int resource, String label, boolean selected, Runnable action) {
            this(context);
            styleButton(this, selected, action);
            setIcon(resource, label);
        }
        void setIcon(int value, String description) {
            if (resource != value) {
                resource = value;
                icon = AppCompatResources.getDrawable(getContext(), value);
                if (icon == null) throw new IllegalArgumentException("Missing media icon.");
                icon = icon.mutate();
                icon.setTint(isSelected() ? Color.WHITE : Color.BLACK);
                invalidate();
            }
            if (!label.equals(description)) {
                label = description;
                setContentDescription(label);
            }
        }
        @Override protected void onDraw(Canvas canvas) {
            if (icon == null) return;
            int size = Math.min(dp(getContext(), isSelected() ? 30 : 24), Math.min(getWidth(), getHeight()));
            int left = (getWidth() - size) / 2, top = (getHeight() - size) / 2;
            icon.setAlpha(isEnabled() ? 255 : 96);
            icon.setBounds(left, top, left + size, top + size);
            icon.draw(canvas);
        }
    }
    static void flatSeekBar(SeekBar seek) {
        Context context = seek.getContext();
        seek.setBackground(null);
        seek.setForeground(null);
        seek.setStateListAnimator(null);
        seek.setElevation(0);
        seek.setSplitTrack(false);
        seek.setTickMark(null);
        seek.setProgressTintList(null);
        seek.setProgressBackgroundTintList(null);
        seek.setThumbTintList(null);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(Color.BLACK);
        thumb.setSize(dp(context, 12), dp(context, 12));
        seek.setThumb(thumb);
        GradientDrawable track = border(context, false);
        GradientDrawable progress = new GradientDrawable();
        progress.setColor(Color.BLACK);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{
                track, new ClipDrawable(progress, Gravity.START, ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        for (int i = 0; i < 2; i++) {
            layers.setLayerHeight(i, dp(context, 2));
            layers.setLayerGravity(i, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
        }
        seek.setProgressDrawable(layers);
    }
    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.WHITE);
        layout.setLayoutTransition(null);
        return layout;
    }
    static LinearLayout row(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }
    static void line(LinearLayout parent) {
        line(parent, Color.BLACK);
    }
    static void line(LinearLayout parent, int color) {
        View line = new View(parent.getContext());
        line.setBackgroundColor(color);
        parent.addView(line, new LinearLayout.LayoutParams(-1, dp(parent.getContext(), 1)));
    }
    static void enabled(Button button, boolean enabled) {
        if (button.isEnabled() != enabled) button.setEnabled(enabled);
        int color = button instanceof IconButton && button.isSelected() ? (enabled ? Color.WHITE : Color.LTGRAY)
                : enabled ? Color.BLACK : Color.DKGRAY;
        if (button.getCurrentTextColor() != color) button.setTextColor(color);
        String label = button instanceof IconButton icon ? icon.label : button.getText().toString();
        button.setContentDescription(label + (enabled ? "" : "，不可用"));
    }
    static void singleLine(TextView text) {
        text.setMaxLines(1);
        text.setEllipsize(TextUtils.TruncateAt.END);
    }

    static final class Cover extends View {
        private final Paint paint = new Paint();
        private final Rect destination = new Rect();
        private final ColorMatrixColorFilter grayscale;
        private Bitmap bitmap;
        Cover(Context context) {
            super(context);
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0);
            grayscale = new ColorMatrixColorFilter(matrix);
            setContentDescription("专辑封面");
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void setBitmap(Bitmap value) {
            if (bitmap == value) return;
            bitmap = value;
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int size = Math.min(getWidth(), getHeight());
            int x = (getWidth() - size) / 2, y = (getHeight() - size) / 2;
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(x, y, x + size, y + size, paint);
            if (bitmap != null) {
                paint.setColorFilter(grayscale);
                paint.setFilterBitmap(false);
                destination.set(x, y, x + size, y + size);
                canvas.drawBitmap(bitmap, null, destination, paint);
                paint.setColorFilter(null);
            } else {
                paint.setColor(Color.BLACK);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(getContext(), 1));
                float cx = x + size / 2f, cy = y + size / 2f;
                for (int ring = 0; ring < 5; ring++) {
                    canvas.drawCircle(cx, cy, size * (0.18f + ring * 0.055f), paint);
                }
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy, size * 0.04f, paint);
            }
        }
    }
}
