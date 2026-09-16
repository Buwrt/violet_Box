package com.violet.box.ui.repo;

import android.annotation.SuppressLint;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.violet.box.R;

import java.util.ArrayList;
import java.util.List;

/** Catalog rows. Row tap opens the version picker, the inline button grabs the newest release. */
final class ModuleRepoAdapter extends RecyclerView.Adapter<ModuleRepoAdapter.Holder> {

    interface Listener {
        void onPickVersion(ModuleEntry entry);
        void onDownloadLatest(ModuleEntry entry);
        /** 长按卡片：把该模块的开源地址复制到剪贴板。 */
        void onCopyUrl(ModuleEntry entry);
    }

    private List<ModuleEntry> data = new ArrayList<>();
    private final Listener listener;

    ModuleRepoAdapter(List<ModuleEntry> initial, Listener listener) {
        this.data = initial != null ? initial : new ArrayList<>();
        this.listener = listener;
    }

    @SuppressLint("NotifyDataSetChanged")
    void update(List<ModuleEntry> next) {
        this.data = next != null ? next : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_module_repo, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(data.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        private final TextView type;
        private final TextView group;
        private final TextView name;
        private final TextView desc;
        private final TextView author;
        private final TextView repo;
        private final TextView download;

        Holder(@NonNull View itemView) {
            super(itemView);
            type = itemView.findViewById(R.id.tvRepoItemType);
            group = itemView.findViewById(R.id.tvRepoItemGroup);
            name = itemView.findViewById(R.id.tvRepoItemName);
            desc = itemView.findViewById(R.id.tvRepoItemDesc);
            author = itemView.findViewById(R.id.tvRepoItemAuthor);
            repo = itemView.findViewById(R.id.tvRepoItemRepo);
            download = itemView.findViewById(R.id.tvRepoItemDownload);
        }

        void bind(ModuleEntry entry, Listener listener) {
            name.setText(entry.name);
            desc.setText(entry.desc);
            group.setText(entry.group);
            repo.setText(entry.owner + "/" + entry.repo);

            boolean kpm = entry.isKpm();
            boolean lsp = entry.isLsp();
            type.setText(kpm ? "KPM" : lsp ? "LSP" : "ZIP");
            // 三种产物用三种颜色区分：ZIP = 紫罗兰（主色），KPM = 青，LSP = 绿（LSPosed 系）
            Drawable badge = type.getBackground();
            if (badge != null) {
                int color = itemView.getResources().getColor(kpm
                        ? com.violet.box.R.color.explore_cyan_600
                        : lsp ? com.violet.box.R.color.explore_emerald_600
                        : com.violet.box.R.color.ios_accent);
                badge.mutate();
                badge.setTint(color);
                type.setBackground(badge);
            }
            download.setText("下载最新");

            // 来源标注：官方（原作者仓库）蓝色小字，镜像（官方模块仓库）灰色小字，作者都要写清楚
            if (author != null) {
                if (entry.official) {
                    author.setText("官方 · 作者 " + entry.author);
                    author.setTextColor(itemView.getResources()
                            .getColor(com.violet.box.R.color.explore_blue_600));
                } else if (entry.upstream != null && entry.upstream.contains("/")) {
                    author.setText("镜像 · 原作者 " + entry.upstream.split("/")[0]);
                    author.setTextColor(itemView.getResources()
                            .getColor(com.violet.box.R.color.explore_slate_500));
                } else {
                    author.setText("镜像 · 官方模块仓库 " + entry.owner);
                    author.setTextColor(itemView.getResources()
                            .getColor(com.violet.box.R.color.explore_slate_500));
                }
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onPickVersion(entry);
            });
            download.setOnClickListener(v -> {
                if (listener != null) listener.onDownloadLatest(entry);
            });
            itemView.setOnLongClickListener(v -> {
                if (listener != null) listener.onCopyUrl(entry);
                return true;
            });
        }
    }
}
