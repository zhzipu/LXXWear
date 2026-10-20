package cn.wearbbs.music.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import cn.wearbbs.music.fragment.ConsoleFragment;
import cn.wearbbs.music.fragment.PlayerPagerFragment;
import cn.wearbbs.music.fragment.QueueFragment;
import cn.wearbbs.music.fragment.SongListFragment;

/**
 * 主界面四列，左右滑动切换（播放器在中间，两边各一页）：
 * <ol>
 *   <li>歌单：在播放器左边，从播放器往右滑到；</li>
 *   <li>播放器列：播放器页 + 它下面的歌词页（上下滑动切，见 {@link PlayerPagerFragment}）；</li>
 *   <li>播放列表：在播放器右边，从播放器往左滑到，就是播放器当前这一队列；</li>
 *   <li>控制台。</li>
 * </ol>
 */
public class ViewPagerAdapter extends FragmentStateAdapter {

    /** 歌单页：播放器左边那页。 */
    public static final int PAGE_SONGLIST = 0;
    /** 播放器列（播放器 + 歌词）的页号，各处跳转「回播放器」都用它。 */
    public static final int PAGE_PLAYER = 1;
    /** 播放列表（当前播放队列）：播放器右边那页。 */
    public static final int PAGE_PLAYLIST = 2;
    public static final int PAGE_CONSOLE = 3;

    public ViewPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (position == PAGE_SONGLIST) {
            return new SongListFragment();
        } else if (position == PAGE_PLAYLIST) {
            return new QueueFragment();
        } else if (position == PAGE_CONSOLE) {
            return new ConsoleFragment();
        }
        return new PlayerPagerFragment();
    }

    @Override
    public int getItemCount() {
        return 4;
    }
}
