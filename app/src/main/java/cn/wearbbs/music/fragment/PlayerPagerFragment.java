package cn.wearbbs.music.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import cn.wearbbs.music.R;
import cn.wearbbs.music.widget.VerticalPagerLayout;

/**
 * 播放器这一列：上面是播放器页，下面是歌词页，上滑/下滑在两者之间翻。
 * <p>
 * 两页都挂在自己的 childFragmentManager 上（同时存在，只是有一页在屏幕外），
 * 所以切来切去不会重建播放器、也不会重新绑定播放服务。
 * <p>
 * 停在歌词页时外层 ViewPager2 的左右翻页会被关掉：歌词页整页是 LrcView，
 * 横向滑很容易误触跑到播放列表去。回播放器页点顶部时间栏即可。
 */
public class PlayerPagerFragment extends Fragment {

    private VerticalPagerLayout pager;
    /** 外层的横向 ViewPager2，用来在歌词页临时禁用左右翻页。 */
    private ViewPager2 hostPager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_player_pager, container, false);
        pager = view.findViewById(R.id.vp_player_pages);
        // 歌词页整页是 LrcView，竖向拖动要留给它做歌词跳转，所以关掉「下滑返回」；
        // 回到播放器页点顶部时间栏。
        pager.setReturnEnabled(false);
        // 停在哪一页决定外层还能不能左右滑（歌词页禁掉）
        pager.setOnPageChangeListener(this::applyHostPaging);

        // 配置变更/进程重建后这两页会被自动恢复，只有第一次进来才需要添加。
        // 用 commitNow 是为了让两页在 onCreateView 返回时就都有视图，
        // 免得翻页动画开始时下面那页还是空白；AllowingStateLoss 只是防页面在后台被创建时抛异常。
        if (getChildFragmentManager().findFragmentById(R.id.fl_player_page) == null) {
            getChildFragmentManager().beginTransaction()
                    .add(R.id.fl_player_page, new PlayerFragment())
                    .add(R.id.fl_lyrics_page, new LyricsFragment())
                    .commitNowAllowingStateLoss();
        }
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 这时视图已经挂进外层 pager，parent 链才找得到，按当前页重新对一次
        applyHostPaging(pager == null ? 0 : pager.getPage());
    }

    @Override
    public void onDestroyView() {
        // 别把外层 pager 留在「禁止左右滑」的状态里
        if (hostPager != null) {
            hostPager.setUserInputEnabled(true);
        }
        pager = null;
        super.onDestroyView();
    }

    /** 回到播放器页（点歌、同步完回来时用），免得停在上次的歌词页上。 */
    public void showPlayerPage() {
        if (pager != null && pager.getPage() != 0) {
            pager.setPage(0, false);
        }
    }

    /** 当前是不是正停在歌词页。 */
    public boolean isOnLyricsPage() {
        return pager != null && pager.getPage() == 1;
    }

    /**
     * 歌词页禁止左右翻页：横向滑不再切到播放列表，只能用顶部时间栏回播放器。
     */
    private void applyHostPaging(int page) {
        ViewPager2 host = findHostPager();
        if (host != null) {
            host.setUserInputEnabled(page == 0);
        }
    }

    /**
     * 顺着 parent 链找外层的 ViewPager2（ViewPager2 里面套着一层 RecyclerView，
     * 本列的视图挂在它下面）。onCreateView 阶段还没挂上去，所以懒找并缓存。
     */
    private ViewPager2 findHostPager() {
        if (hostPager != null) {
            return hostPager;
        }
        View root = getView();
        ViewParent parent = root == null ? null : root.getParent();
        while (parent instanceof ViewGroup) {
            if (parent instanceof ViewPager2) {
                hostPager = (ViewPager2) parent;
                return hostPager;
            }
            parent = parent.getParent();
        }
        return null;
    }
}
