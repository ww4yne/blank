package app.moting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

final class MusicData {
    record Item(String id, String type, String title, String subtitle,
                String catalogId, String artwork) {
        boolean song() { return type.equals("songs") || type.equals("library-songs"); }
        boolean library() { return type.startsWith("library-"); }
        boolean playlist() { return type.equals("playlists") || type.equals("library-playlists"); }
        boolean group() { return type.equals("recommendation-groups"); }
    }

    record Group(String id, String title, List<Item> items, String next) {}
    record Page(List<Item> items, String next, List<Group> groups) {
        Page(List<Item> items, String next) { this(items, next, List.of()); }
    }

    static Page recommendations(JSONObject response) throws JSONException {
        Map<String, JSONObject> included = new LinkedHashMap<>();
        index(response, included);
        Map<String, Group> groups = new LinkedHashMap<>();
        Map<String, Item> menus = new LinkedHashMap<>();
        JSONArray data = response.optJSONArray("data");
        if (data == null) throw new JSONException("Apple 未返回推荐列表。");
        for (int i = 0; i < data.length(); i++) {
            JSONObject entry = data.getJSONObject(i);
            if (entry.optString("type").equals("personal-recommendation")) {
                Item menu = recommendation(entry, included, groups, new HashSet<>(), 0);
                if (menu != null) menus.putIfAbsent(menu.type() + ":" + menu.id(), menu);
            } else {
                walk(entry, included, menus, 0);
            }
        }
        return new Page(List.copyOf(menus.values()), response.optString("next", ""), List.copyOf(groups.values()));
    }

    private static Item recommendation(JSONObject reference, Map<String, JSONObject> included,
                                       Map<String, Group> groups, Set<String> path, int depth) throws JSONException {
        if (depth > 24) throw new JSONException("Recommendation nesting is too deep.");
        String id = reference.getString("id");
        String type = reference.getString("type");
        if (!type.equals("personal-recommendation")) return null;
        if (!path.add(id)) throw new JSONException("Apple 返回了循环的推荐分类。");
        JSONObject full = included.getOrDefault(type + ":" + id, reference);
        JSONObject attrs = full.optJSONObject("attributes");
        String title = display(attrs, "title");
        if (title.isEmpty()) title = attrs != null && attrs.optString("kind").equals("recently-played")
                ? "最近播放" : "Apple Music 推荐";
        String reason = display(attrs, "reason");
        Map<String, Item> contents = new LinkedHashMap<>();
        JSONObject relationships = full.optJSONObject("relationships");
        JSONObject relation = relationships == null ? null : relationships.optJSONObject("contents");
        JSONArray entries = relation == null ? null : relation.optJSONArray("data");
        if (entries != null) {
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                if (entry.optString("type").equals("personal-recommendation")) {
                    Item child = recommendation(entry, included, groups, path, depth + 1);
                    if (child != null) contents.putIfAbsent(child.type() + ":" + child.id(), child);
                } else {
                    walk(entry, included, contents, 0);
                }
            }
        }
        path.remove(id);
        String next = relation == null ? "" : relation.optString("next", "");
        if (contents.isEmpty() && next.isEmpty()) return null;
        groups.put(id, new Group(id, title, List.copyOf(contents.values()), next));
        return new Item(id, "recommendation-groups", title, reason, "", "");
    }

    private static String display(JSONObject attrs, String key) {
        if (attrs == null) return "";
        Object value = attrs.opt(key);
        if (value instanceof JSONObject object) return object.optString("stringForDisplay", "");
        return value instanceof String string ? string : "";
    }

    static Page parse(JSONObject response) throws JSONException {
        Map<String, JSONObject> included = new LinkedHashMap<>();
        index(response, included);
        Map<String, Item> items = new LinkedHashMap<>();
        walk(response.opt("data"), included, items, 0);
        if (response.has("results")) {
            walk(response.getJSONObject("results"), included, items, 0);
        }
        String next = response.optString("next", "");
        JSONObject results = response.optJSONObject("results");
        if (next.isEmpty() && results != null && results.length() == 1) {
            JSONObject category = results.optJSONObject(results.keys().next());
            if (category != null) next = category.optString("next", "");
        }
        return new Page(List.copyOf(items.values()), next);
    }

    private static boolean supported(String type) {
        return switch (type) {
            case "songs", "library-songs", "albums", "library-albums",
                    "playlists", "library-playlists" -> true;
            default -> false;
        };
    }

    private static void index(Object node, Map<String, JSONObject> index) throws JSONException {
        if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) index(array.get(i), index);
        } else if (node instanceof JSONObject object) {
            if (object.has("id") && object.has("type") && (object.has("attributes") || object.has("relationships"))) {
                index.put(object.getString("type") + ":" + object.getString("id"), object);
            }
            for (var keys = object.keys(); keys.hasNext();) index(object.get(keys.next()), index);
        }
    }

    private static void walk(Object node, Map<String, JSONObject> index,
                             Map<String, Item> out, int depth) throws JSONException {
        if (depth > 24) throw new JSONException("Recommendation nesting is too deep.");
        if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) walk(array.get(i), index, out, depth + 1);
        } else if (node instanceof JSONObject object) {
            String type = object.optString("type");
            String id = object.optString("id");
            if (supported(type)) {
                JSONObject full = index.getOrDefault(type + ":" + id, object);
                JSONObject attrs = full.optJSONObject("attributes");
                if (attrs != null && !attrs.optString("name").isEmpty()) {
                    JSONObject params = attrs.optJSONObject("playParams");
                    String catalogId = params == null ? "" : params.optString("catalogId");
                    if (!type.startsWith("library-") && params != null) {
                        catalogId = params.optString("id", id);
                    }
                    JSONObject artwork = attrs.optJSONObject("artwork");
                    String subtitle = attrs.optString("artistName",
                            attrs.optString("curatorName", type.contains("playlist") ? "Apple Music 歌单" : "专辑"));
                    out.putIfAbsent(type + ":" + id, new Item(id, type, attrs.getString("name"),
                            subtitle, catalogId, artwork == null ? "" : artwork.optString("url")));
                }
                return;
            }
            if (type.equals("stations")) return;
            for (var keys = object.keys(); keys.hasNext();) {
                String key = keys.next();
                if (!key.equals("attributes") && !key.equals("included")) {
                    walk(object.get(key), index, out, depth + 1);
                }
            }
        }
    }

    static List<Item> songs(List<Item> items) {
        List<Item> result = new ArrayList<>();
        for (Item item : items) if (item.song()) result.add(item);
        return result;
    }

    static Page parseTracks(JSONObject response) throws JSONException {
        JSONArray data = response.optJSONArray("data");
        if (data == null) throw new JSONException("Apple 未返回曲目列表。");
        List<Item> tracks = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject single = new JSONObject().put("data", new JSONArray().put(data.getJSONObject(i)));
            List<Item> parsed = parse(single).items();
            if (parsed.size() != 1 || !parsed.get(0).song()) {
                throw new JSONException("列表包含视频或不可识别的曲目；本版只支持 Apple Music 音频歌曲。");
            }
            tracks.add(parsed.get(0));
        }
        return new Page(List.copyOf(tracks), response.optString("next", ""));
    }
}
