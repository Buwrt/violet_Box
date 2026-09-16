package com.violet.box.ui.repo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One row of the built-in module catalog (assets/module_repo.json). */
public final class ModuleEntry {
    public final String id;
    public final String name;
    /**
     * "zip" (Magisk/KSU/APatch-APM 刷入包), "kpm" (KernelPatch 内核模块)
     * 或 "lsp" (LSPosed / Xposed 模块，产物是 apk)。
     */
    public final String type;
    public final String group;
    public final String owner;
    public final String repo;
    public final String assetSuffix;
    public final String assetKeyword;
    public final String author;
    public final String desc;
    /**
     * true = 原作者自己的仓库（官方）；false = 官方模块仓库镜像（Xposed-Modules-Repo 等）。
     * 官方条目在列表里用蓝色小字标注，镜像条目用灰色，两者都会收录。
     */
    public final boolean official;
    /** 镜像条目对应的原作者仓库（owner/repo），官方条目为空。 */
    public final String upstream;
    /** 代码托管平台："github"（默认）或 "gitlab"。 */
    public final String host;

    ModuleEntry(String id, String name, String type, String group, String owner, String repo,
                String assetSuffix, String assetKeyword, String author, String desc,
                boolean official, String upstream, String host) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.group = group;
        this.owner = owner;
        this.repo = repo;
        this.assetSuffix = assetSuffix;
        this.assetKeyword = assetKeyword;
        this.author = author;
        this.desc = desc;
        this.official = official;
        this.upstream = upstream;
        this.host = host;
    }

    public boolean isKpm() {
        return "kpm".equalsIgnoreCase(type);
    }

    public boolean isLsp() {
        return "lsp".equalsIgnoreCase(type);
    }

    public boolean isGitlab() {
        return "gitlab".equalsIgnoreCase(host);
    }

    /** 开源地址，长按卡片复制的就是它。 */
    public String sourceUrl() {
        return isGitlab()
                ? "https://gitlab.com/" + owner + "/" + repo
                : "https://github.com/" + owner + "/" + repo;
    }

    static List<ModuleEntry> parseList(JSONArray array) throws Exception {
        List<ModuleEntry> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i);
            out.add(new ModuleEntry(
                    o.optString("id", ""),
                    o.optString("name", ""),
                    o.optString("type", "zip"),
                    o.optString("group", ""),
                    o.optString("owner", ""),
                    o.optString("repo", ""),
                    o.optString("assetSuffix", ".zip"),
                    o.optString("assetKeyword", ""),
                    o.optString("author", ""),
                    o.optString("desc", ""),
                    // 老清单没有这个字段，默认按官方处理（绝大多数条目本来就是原作者仓库）
                    o.optBoolean("official", true),
                    o.optString("upstream", ""),
                    o.optString("host", "github")
            ));
        }
        return out;
    }
}
