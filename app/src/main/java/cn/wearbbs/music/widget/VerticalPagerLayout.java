package cn.wearbbs.music.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Scroller;

/**
 * 上下翻页容器：手指上滑露出下一页，下滑回到上一页。
 * <p>
 * 播放器页和歌词页就放在这里，歌词是播放器的「下面一页」。
 * 只用在这一个地方，所以没有做通用翻页器的那些东西（循环、拦点击等）：
 * <ul>
 *   <li>只管竖向滑动，横向滑动原样放给外层的 ViewPager2（左滑去播放列表）；</li>
 *   <li>松手后按「滑过页高 30% 或甩得够快」决定翻不翻，否则弹回原页；</li>
 *   <li>第二页（歌词）整页被 LrcView 占着，而 LrcView 自己要用竖向拖动做歌词跳转，
 *       所以第二页的下滑返回默认关掉（{@link #setReturnEnabled(boolean)}），
 *       它的竖向拖动全交给歌词页自己；回播放器页靠点顶部时间栏。</li>
 * </ul>
 */
public class VerticalPagerLayout extends ViewGroup {

    /** 翻页完成回调。 */
    public interface OnPageChangeListener {
        void onPageSelected(int page);
    }

    /** 弹回/翻过去动画时长。 */
    private static final int SETTLE_DURATION = 220;
    /** 松手时滑过页高的这个比例就算翻页。 */
    private static final float SETTLE_RATIO = 0.3f;
    /** 竖向位移要明显大于横向，才认定为上下滑，避免抢掉外层左右滑。 */
    private static final float VERTICAL_BIAS = 1.6f;

    private final Scroller scroller;
    private final VelocityTracker velocityTracker = VelocityTracker.obtain();

    private final int touchSlop;
    private final int minFlingVelocity;
    private final int maxFlingVelocity;

    private OnPageChangeListener listener;

    /** 当前停在第几页。 */
    private int page;
    /** 是否正在跟着手指拖。 */
    private boolean dragging;
    /** 是否正在播放翻页动画。 */
    private boolean settling;
    /** 本次手势是否允许被本容器接管（歌词页默认不接管，竖向拖动留给 LrcView）。 */
    private boolean gestureAllowed;
    /** 手势开始时的滚动位置。 */
    private int startScroll;
    private float downRawX;
    private float downRawY;
    /** 是否允许从第二页往下滑回第一页。歌词页要整页吃竖向拖动，所以默认会被关掉。 */
    private boolean returnEnabled = true;

    public VerticalPagerLayout(Context context) {
        this(context, null);
    }

    public VerticalPagerLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        scroller = new Scroller(context);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        // 手表屏幕小、手指也小，阈值放宽一点，免得点按被判成滑动
        touchSlop = configuration.getScaledTouchSlop() * 2;
        minFlingVelocity = configuration.getScaledMinimumFlingVelocity() * 2;
        maxFlingVelocity = configuration.getScaledMaximumFlingVelocity();
        // 自己吃掉 ACTION_DOWN（哪怕按在空白处），否则父级 ViewPager2 会越过我们
        // 直接当触摸目标，后续 MOVE 就轮不到 onInterceptTouchEvent，上滑永远翻不了页
        setClickable(true);
    }

    public void setOnPageChangeListener(OnPageChangeListener listener) {
        this.listener = listener;
    }

    /**
     * 允不允许从第二页往下滑回第一页。
     * <p>
     * 第二页是歌词页，整页被 LrcView 占着，竖向拖动是它的「拖歌词跳转」，所以这里关掉：
     * 回播放器页走点顶部时间栏那条路（见 MainActivity）。
     */
    public void setReturnEnabled(boolean enabled) {
        returnEnabled = enabled;
    }

    public int getPage() {
        return page;
    }

    /** 直接跳到某一页。 */
    public void setPage(int target, boolean smooth) {
        int count = getChildCount();
        if (count == 0) {
            return;
        }
        int fixed = Math.max(0, Math.min(count - 1, target));
        int height = getHeight();
        if (height <= 0) {
            // 还没量出高度：先记下，等 onLayout 再摆
            page = fixed;
            return;
        }
        if (!smooth || dragging) {
            scroller.forceFinished(true);
            settling = false;
            scrollTo(0, fixed * height);
            changePage(fixed);
            return;
        }
        if (fixed == page && getScrollY() == fixed * height) {
            return;
        }
        settleTo(fixed * height);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
        // 每页都占满整屏，高度固定，翻页时不用重新测量
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = getMeasuredWidth();
        int height = getMeasuredHeight();
        for (int i = 0; i < getChildCount(); i++) {
            // 第 i 页摆在第 i 屏的位置，靠 scrollTo 决定露出哪一页
            getChildAt(i).layout(0, i * height, width, (i + 1) * height);
        }
        if (changed && !dragging) {
            scroller.forceFinished(true);
            settling = false;
            int count = getChildCount();
            page = Math.max(0, Math.min(count - 1, page));
            scrollTo(0, page * height);
        }
    }

    /** 子页里都是可点的按钮，别让系统延迟按下态，点按才跟手。 */
    @Override
    public boolean shouldDelayChildPressedState() {
        return false;
    }

    private int maxScroll() {
        int count = getChildCount();
        return count <= 1 ? 0 : (count - 1) * getHeight();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (getChildCount() < 2) {
            return false;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = ev.getRawX();
                downRawY = ev.getRawY();
                dragging = false;
                if (settling) {
                    // 动画没播完又按下去：停在原地，重新判断
                    scroller.forceFinished(true);
                    settling = false;
                }
                // 动画中途被按下时 page 可能还是旧的旧值，按当前滚动位置重新认一下，
                // 否则会以为「已经在第一页」而不让下滑
                int height = getHeight();
                if (height > 0) {
                    page = Math.max(0, Math.min(getChildCount() - 1, Math.round((float) getScrollY() / height)));
                }
                startScroll = getScrollY();
                gestureAllowed = isGestureAllowed();
                velocityTracker.clear();
                velocityTracker.addMovement(ev);
                return false;

            case MotionEvent.ACTION_MOVE: {
                velocityTracker.addMovement(ev);
                if (dragging || !gestureAllowed) {
                    return false;
                }
                float dy = ev.getRawY() - downRawY;
                float dx = ev.getRawX() - downRawX;
                if (Math.abs(dy) <= touchSlop || Math.abs(dy) < Math.abs(dx) * VERTICAL_BIAS) {
                    return false;
                }
                boolean toNextPage = dy < 0;
                int count = getChildCount();
                if (toNextPage ? page >= count - 1 : page <= 0) {
                    // 已经到头了，这一下不算翻页，交回给子页
                    return false;
                }
                dragging = true;
                // 别让外层 ViewPager2 同时开始横向拖动
                ViewParent parent = getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                velocityTracker.clear();
                return false;

            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) {
                    return true;
                }
                velocityTracker.addMovement(ev);
                int target = startScroll - (int) (ev.getRawY() - downRawY);
                scrollTo(0, clampScroll(target));
                return true;
            }

            case MotionEvent.ACTION_UP: {
                if (!dragging) {
                    return true;
                }
                dragging = false;
                velocityTracker.addMovement(ev);
                velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
                float velocityY = velocityTracker.getYVelocity();
                velocityTracker.clear();
                int current = getScrollY();
                int max = maxScroll();
                int target;
                if (current > startScroll) {
                    // 手指往上，正在翻向下一页
                    target = (velocityY < -minFlingVelocity || current > max * SETTLE_RATIO) ? max : 0;
                } else {
                    // 手指往下，正在退回上一页
                    target = (velocityY > minFlingVelocity || current < max * (1 - SETTLE_RATIO)) ? 0 : max;
                }
                settleTo(target);
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                settleTo(page * getHeight());
                return true;

            default:
                return true;
        }
    }

    /**
     * 这一次手势能不能被本容器接管。
     * 播放器页（第 0 页）整页都可以上滑；歌词页默认不让接管，竖向拖动留给 LrcView。
     */
    private boolean isGestureAllowed() {
        return page <= 0 || returnEnabled;
    }

    private int clampScroll(int y) {
        return Math.max(0, Math.min(maxScroll(), y));
    }

    private void settleTo(int target) {
        int fixed = clampScroll(target);
        int delta = fixed - getScrollY();
        if (delta == 0) {
            changePage(getHeight() <= 0 ? 0 : Math.round((float) fixed / getHeight()));
            return;
        }
        settling = true;
        scroller.startScroll(0, getScrollY(), 0, delta, SETTLE_DURATION);
        postInvalidateOnAnimation();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(0, scroller.getCurrY());
            postInvalidateOnAnimation();
            return;
        }
        if (settling) {
            settling = false;
            int height = getHeight();
            int snapped = height <= 0 ? 0 : Math.round((float) getScrollY() / height);
            scrollTo(0, snapped * height);
            changePage(snapped);
        }
    }

    private void changePage(int newPage) {
        if (newPage == page) {
            return;
        }
        page = newPage;
        if (listener != null) {
            listener.onPageSelected(newPage);
        }
    }
}
