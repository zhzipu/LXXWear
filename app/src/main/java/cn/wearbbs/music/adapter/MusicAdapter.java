package cn.wearbbs.music.adapter;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;

import java.util.List;

import cn.wearbbs.music.R;
import cn.wearbbs.music.model.Song;
import cn.wearbbs.music.player.PlaylistProvider;

/**
 * 播放列表的列表项，当前播放的一首用高亮色区分。
 */
public class MusicAdapter extends RecyclerView.Adapter<MusicAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(int position);
    }

    private final Context context;
    private final List<Song> songs;
    private final OnItemClickListener listener;

    public MusicAdapter(Context context, List<Song> songs, OnItemClickListener listener) {
        this.context = context;
        this.songs = songs;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(context).inflate(R.layout.item_song, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Song song = songs.get(position);

        holder.tvTitle.setText(TextUtils.isEmpty(song.getName())
                ? context.getString(R.string.untitled)
                : song.getName());
        holder.tvArtists.setText(TextUtils.isEmpty(song.getArtist())
                ? context.getString(R.string.unknown)
                : song.getArtist());
        // 正在播的这首固定用品牌蓝：夜间主题里 colorPrimaryVariant 是近白色，
        // 拿它当高亮会和普通标题看不出区别，所以这里直接取 blue_500。
        holder.tvTitle.setTextColor(isCurrent(song, position)
                ? ContextCompat.getColor(context, R.color.blue_500)
                : resolveColor(R.attr.colorOnSecondary));

        RequestOptions options = RequestOptions.bitmapTransform(new RoundedCorners(10))
                .placeholder(R.drawable.ic_baseline_photo_size_select_actual_24)
                .error(R.drawable.ic_baseline_photo_size_select_actual_24);
        holder.ivCover.setImageResource(R.drawable.ic_baseline_photo_size_select_actual_24);
        if (!TextUtils.isEmpty(song.getCoverUrl())) {
            Glide.with(context).load(song.getCoverUrl()).apply(options).into(holder.ivCover);
        }

        holder.llMain.setOnClickListener(v -> {
            int position1 = holder.getAdapterPosition();
            if (position1 != RecyclerView.NO_POSITION) {
                listener.onItemClick(position1);
            }
        });
    }

    @Override
    public int getItemCount() {
        return songs.size();
    }

    /**
     * 是不是正在播的那首。优先按 id 比，这样同一张歌单从别处取回来（列表对象不同）也能对上；
     * 没有 id（assets 示例歌）时退回比下标。
     */
    private boolean isCurrent(Song song, int position) {
        Song current = PlaylistProvider.getCurrent();
        if (current != null && !TextUtils.isEmpty(current.getId())) {
            return current.getId().equals(song.getId());
        }
        return position == PlaylistProvider.getCurrentIndex();
    }

    /** 主题里定义的颜色，明暗主题都能取到对应值。 */
    private int resolveColor(int attr) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true) && value.resourceId != 0) {
            return ContextCompat.getColor(context, value.resourceId);
        }
        return value.data;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivCover;
        TextView tvTitle;
        TextView tvArtists;
        LinearLayout llMain;

        ViewHolder(View itemView) {
            super(itemView);
            ivCover = itemView.findViewById(R.id.iv_cover);
            tvTitle = itemView.findViewById(R.id.tv_title);
            tvArtists = itemView.findViewById(R.id.tv_artists);
            llMain = itemView.findViewById(R.id.ll_main);
        }
    }
}
