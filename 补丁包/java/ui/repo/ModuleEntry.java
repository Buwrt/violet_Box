package com.violet.box.ui.repo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One row of the built-in module catalog (assets/module_repo.json). */
public final class ModuleEntry {
    public final String id;
    public final String name;
    /** "zip" (Magisk/KSU/APatch-APM) or "kpm" (KernelPatch kernel module). */
    public final String type;
    public final String group;
    public final String owner;
    public final String repo;
    public final String assetSuffix;
    public final String assetKeyword;
    public final String author;
    public final String desc;

    ModuleEntry(String id, String name, String type, String group, String owner, String repo,
                String assetSuffix, String assetKeyword, String author, String desc) {
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
    }

    public boolean isKpm() {
        return "kpm".equalsIgnoreCase(type);
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
                    o.optString("desc", "")
            ));
        }
        return out;
    }
}
