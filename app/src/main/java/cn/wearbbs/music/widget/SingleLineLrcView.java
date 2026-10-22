package cn.wearbbs.music.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单行歌词条：控件本身只有一行的高度，停稳时只露出当前这一句。
 * <p>
 * 竖向：换句时整条歌词像纸带一样往上滚——上一句向上滑出、下一句从下面顶上来；
 * 往回拖进度条时方向反过来。滚动过程中上下两句各露一半，滚停后仍旧只有一行；
 * 再往外的那几句都被视图边界裁掉。
 * <p>
 * 横向：一句比控件宽的时候，从左边开始从右向左慢慢滚一次，滚到能看见句尾就停在那里
 * （不循环、不回弹）；放得下的句子居中显示。
 * <p>
 * 播放器页「歌手」那一行在播放时换成它。
 */
public class SingleLineLrcView extends View {

    /** 换句竖向滚动的时长。 */
    private static final long SCROLL_DURATION = 360L;
    /** 相邻两句的行距 = 自身高度 × 该系数。 */
    private static final float LINE_GAP = 1.0f;
    /** 横向滚动的速度：每秒走多少 dp，太快看不清、太慢句子还没走完就该换句了。 */
    private static final float MARQUEE_SPEED_DP = 55f;
    private static final long MARQUEE_MIN_MS = 700L;
    private static final long MARQUEE_MAX_MS = 6000L;
    /** 开滚前先停一下，让句首能看清楚。 */
    private static final long MARQUEE_START_DELAY_MS = 600L;

    /** 时间轴 [mm:ss]、[mm:ss.x]、[mm:ss.xx]、[mm:ss.xxx]。 */
    private static final Pattern TIME_TAG =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    /** 整体偏移标签 [offset:+500]。 */
    private static final Pattern OFFSET_TAG =
            Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);

    private final TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    private final List<Line> lines = new ArrayList<>();

    private float textSize;
    private int textColor;

    /** 当前这一句；-1 表示还没走到第一句。 */
    private int current = -1;
    /** 换句动画里正在滑出的那句。 */
    private int leaving = -1;
    /** 这次的滚动方向：true 为向上滚（往后播），false 为向下滚（往回拖）。 */
    private boolean forward = true;
    /** 换句动画进度，1 表示已停稳。 */
    private float progress = 1f;

    @Nullable
    private ValueAnimator animator;

    /** 当前这句的横向偏移（<= 0，往左为负）；放得下时一直是 0。 */
    private float currentOffset;
    /** 已经为哪一句起过横向滚动，避免同一句重复触发。 */
    private int marqueeIndex = -1;
    @Nullable
    private ValueAnimator marquee;

    /** 已经解析进来的 LRC，内容一样就不重复解析（否则每次刷新都会把当前句清掉）。 */
    @Nullable
    private String loadedLrc;

    /** 量过的句子宽度（同一句不重复量）。 */
    private int cacheIndex = -1;
    private float cacheTextWidth;

    public SingleLineLrcView(Context context) {
        this(context, null);
    }

    public SingleLineLrcView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SingleLineLrcView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12,
                getResources().getDisplayMetrics());
        textColor = 0xFF999999;
        if (attrs != null) {
            TypedArray ta = context.obtainStyledAttributes(attrs,
                    new int[]{android.R.attr.textSize, android.R.attr.textColor});
            textSize = ta.getDimension(0, textSize);
            ColorStateList colors = ta.getColorStateList(1);
            if (colors != null) {
                textColor = colors.getDefaultColor();
            }
            ta.recycle();
        }
        paint.setTextSize(textSize);
        paint.setColor(textColor);
    }

    /** 换成新歌的歌词；传 null 或空串就是清空。 */
    public void setLrc(@Nullable String lrc) {
        String text = lrc == null ? "" : lrc;
        if (text.equals(loadedLrc)) {
            return;
        }
        loadedLrc = text;
        cancelAnimation();
        cancelMarquee();
        lines.clear();
        current = -1;
        leaving = -1;
        progress = 1f;
        cacheIndex = -1;
        parse(text);
        invalidate();
    }

    /** 有没有可显示的时间轴歌词。 */
    public boolean hasLyric() {
        return !lines.isEmpty();
    }

    /**
     * 按播放位置刷新到对应那句，跨句时播一段竖向滚动。
     * 同一句重复调用不会有任何动作，可以放心地高频调用。
     */
    public void updateTime(long timeMs) {
        if (lines.isEmpty()) {
            return;
        }
        // 还没到第一句的时间也先摆第一句，别让这一行空着
        int index = Math.max(0, indexOf(timeMs));
        if (index == current) {
            // 视图刚量好宽度（或换歌后第一次显示）时补启动横向滚动
            if (marqueeIndex != current) {
                startMarquee();
            }
            return;
        }
        moveTo(index);
    }

    public void setTextSize(float sizePx) {
        textSize = sizePx;
        paint.setTextSize(sizePx);
        cacheIndex = -1;
        marqueeIndex = -1;
        startMarquee();
        invalidate();
    }

    public void setTextColor(int color) {
        textColor = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (current < 0 || current >= lines.size()) {
            return;
        }
        float maxWidth = contentWidth();
        if (maxWidth <= 0f || getHeight() <= 0) {
            return;
        }
        float baseline = getHeight() / 2f - (paint.ascent() + paint.descent()) / 2f;
        // 相邻两句的行距；滚动时两句各错开这么多，看着就是一条连续往上走的歌词带
        float gap = getHeight() * LINE_GAP;

        if (leaving >= 0 && leaving < lines.size() && progress < 1f) {
            // 滑出去的那句按它的句尾位置画，别在半路上突然跳回最左边
            float leavingOffset = endOffset(leaving, maxWidth);
            if (forward) {
                // 往上滚：上一句向上滑出，下一句从下面顶上来
                drawLine(canvas, leaving, baseline - progress * gap, maxWidth, leavingOffset);
                drawLine(canvas, current, baseline + (1f - progress) * gap, maxWidth, currentOffset);
            } else {
                drawLine(canvas, leaving, baseline + progress * gap, maxWidth, leavingOffset);
                drawLine(canvas, current, baseline - (1f - progress) * gap, maxWidth, currentOffset);
            }
            return;
        }
        drawLine(canvas, current, baseline, maxWidth, currentOffset);
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnimation();
        cancelMarquee();
        super.onDetachedFromWindow();
    }

    /**
     * 画一句：放得下就居中；放不下就按 offset 往左偏多少画（0 是最左边，负到底正好露出句尾）。
     */
    private void drawLine(Canvas canvas, int index, float baseline, float maxWidth, float offset) {
        if (index < 0 || index >= lines.size()) {
            return;
        }
        String text = lines.get(index).text;
        if (text.isEmpty()) {
            return;
        }
        paint.setColor(textColor);
        float textWidth = measure(index);
        float x;
        if (textWidth <= maxWidth) {
            x = getPaddingLeft() + (maxWidth - textWidth) / 2f;
        } else {
            float minOffset = maxWidth - textWidth;
            x = getPaddingLeft() + Math.max(minOffset, Math.min(0f, offset));
        }
        canvas.drawText(text, x, baseline, paint);
    }

    /** 内容区宽度（去掉左右 padding）。 */
    private float contentWidth() {
        return getWidth() - getPaddingLeft() - getPaddingRight();
    }

    /** 量一句的宽度，同一句只量一次（字号变了会清缓存）。 */
    private float measure(int index) {
        if (cacheIndex == index) {
            return cacheTextWidth;
        }
        cacheTextWidth = paint.measureText(lines.get(index).text);
        cacheIndex = index;
        return cacheTextWidth;
    }

    /** 这句滚到底时的横向偏移（负数，正好露出句尾）；放得下就是 0。 */
    private float endOffset(int index, float maxWidth) {
        float textWidth = measure(index);
        float distance = textWidth - maxWidth;
        return distance > 0.5f ? -distance : 0f;
    }

    /**
     * 横向滚动：宽出来的那句从左边开始、从右向左滚一次，停在露出句尾的位置。
     * 放得下的句子不动（居中显示）。
     */
    private void startMarquee() {
        cancelMarquee();
        currentOffset = 0f;
        marqueeIndex = current;
        if (current < 0 || current >= lines.size()) {
            invalidate();
            return;
        }
        float maxWidth = contentWidth();
        if (maxWidth <= 0f) {
            // 还没量好宽度：等下一次 updateTime 再补
            marqueeIndex = -1;
            return;
        }
        float distance = measure(current) - maxWidth;
        if (distance <= 0.5f) {
            invalidate();
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        long duration = (long) (distance / (MARQUEE_SPEED_DP * density) * 1000f);
        duration = Math.max(MARQUEE_MIN_MS, Math.min(MARQUEE_MAX_MS, duration));

        ValueAnimator valueAnimator = ValueAnimator.ofFloat(0f, -distance);
        valueAnimator.setDuration(duration);
        valueAnimator.setStartDelay(MARQUEE_START_DELAY_MS);
        valueAnimator.setInterpolator(new LinearInterpolator());
        valueAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                currentOffset = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
        marquee = valueAnimator;
        valueAnimator.start();
    }

    private void cancelMarquee() {
        if (marquee != null) {
            marquee.removeAllUpdateListeners();
            marquee.cancel();
            marquee = null;
        }
    }

    private void moveTo(int index) {
        int old = current;
        current = index;
        cacheIndex = -1;
        marqueeIndex = -1;
        cancelMarquee();
        currentOffset = 0f;
        if (old < 0 || index < 0) {
            // 第一句之前、或换歌后第一次对齐：直接显示，不滚动
            leaving = -1;
            progress = 1f;
            startMarquee();
            invalidate();
            return;
        }
        leaving = old;
        forward = index > old;
        startAnimation();
    }

    /** 竖向换句动画；滚完再把这一句的横向滚动接上。 */
    private void startAnimation() {
        cancelAnimation();
        progress = 0f;
        ValueAnimator valueAnimator = ValueAnimator.ofFloat(0f, 1f);
        valueAnimator.setDuration(SCROLL_DURATION);
        valueAnimator.setInterpolator(new DecelerateInterpolator());
        valueAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                progress = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
        valueAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                leaving = -1;
                progress = 1f;
                startMarquee();
                invalidate();
            }
        });
        animator = valueAnimator;
        valueAnimator.start();
    }

    private void cancelAnimation() {
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
            animator = null;
        }
    }

    /** 找 timeMs 落在哪一句上；都不满足（还没到第一句）返回 -1。 */
    private int indexOf(long timeMs) {
        int low = 0;
        int high = lines.size() - 1;
        int found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).time <= timeMs) {
                found = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return found;
    }

    /**
     * 解析 LRC：一行可以带多个时间标签（同一句重复出现），[offset:] 整体平移，
     * 没有任何时间标签的纯文本歌词整段当一句处理。
     */
    private void parse(String lrc) {
        if (TextUtils.isEmpty(lrc.trim())) {
            return;
        }
        long offset = 0L;
        Matcher offsetMatcher = OFFSET_TAG.matcher(lrc);
        if (offsetMatcher.find()) {
            try {
                offset = Long.parseLong(offsetMatcher.group(1));
            } catch (NumberFormatException ignored) {
                offset = 0L;
            }
        }
        for (String raw : lrc.split("\r?\n")) {
            String row = raw.trim();
            if (row.isEmpty()) {
                continue;
            }
            Matcher matcher = TIME_TAG.matcher(row);
            List<Long> stamps = new ArrayList<>(2);
            int textStart = -1;
            while (matcher.find()) {
                stamps.add(toMillis(matcher));
                textStart = matcher.end();
            }
            if (stamps.isEmpty() || textStart < 0) {
                continue;
            }
            String content = row.substring(textStart).trim();
            for (Long stamp : stamps) {
                lines.add(new Line(Math.max(0L, stamp + offset), content));
            }
        }
        if (lines.isEmpty()) {
            lines.add(new Line(0L, lrc.trim().replaceAll("\\s*\\n\\s*", " ")));
            return;
        }
        Collections.sort(lines, new Comparator<Line>() {
            @Override
            public int compare(Line left, Line right) {
                return Long.compare(left.time, right.time);
            }
        });
    }

    private static long toMillis(Matcher matcher) {
        long minute = Long.parseLong(matcher.group(1));
        long second = Long.parseLong(matcher.group(2));
        String fraction = matcher.group(3);
        long millis = 0L;
        if (fraction != null && fraction.length() > 0) {
            if (fraction.length() == 1) {
                millis = Long.parseLong(fraction) * 100L;
            } else if (fraction.length() == 2) {
                millis = Long.parseLong(fraction) * 10L;
            } else {
                millis = Long.parseLong(fraction.substring(0, 3));
            }
        }
        return minute * 60000L + second * 1000L + millis;
    }

    /** 一句歌词。 */
    private static final class Line {
        final long time;
        final String text;

        Line(long time, String text) {
            this.time = time;
            this.text = text;
        }
    }
}
