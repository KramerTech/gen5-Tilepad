package com.tilepad;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure tile-list encoding and editing. One tile per line, tab-separated:
 * {@code kind  component  launcher  label}. Tabs, newlines and backslashes are escaped.
 * Every core tile is always present exactly once; shortcuts are unique per component.
 */
final class TileOrder {
    private static final String HEADER = "tiles/1";

    static List<TileSpec> parse(String stored) {
        List<TileSpec> tiles = new ArrayList<>();
        if (stored != null && stored.startsWith(HEADER + "\n")) {
            for (String line : stored.substring(HEADER.length() + 1).split("\n")) {
                TileSpec tile = parseLine(line);
                if (tile != null) tiles.add(tile);
            }
        }
        return normalize(tiles);
    }

    static String serialize(List<TileSpec> tiles) {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (TileSpec tile : tiles) {
            out.append(tile.kind.name()).append('\t')
                .append(escape(tile.component)).append('\t')
                .append(tile.launcher ? '1' : '0').append('\t')
                .append(escape(tile.label)).append('\n');
        }
        return out.toString();
    }

    /** Drops duplicates and restores missing core tiles at their default position. */
    static List<TileSpec> normalize(List<TileSpec> input) {
        List<TileSpec> tiles = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TileSpec tile : input) if (seen.add(tile.id)) tiles.add(tile);
        for (int i = 0; i < TileSpec.CORE.length; i++) {
            TileSpec.Kind kind = TileSpec.CORE[i];
            if (seen.contains(kind.coreId)) continue;
            int at = Math.min(i, tiles.size());
            if (kind == TileSpec.Kind.TWEAKS) {
                // Migrate custom layouts next to Precondition without moving their shortcuts.
                for (int j = 0; j < tiles.size(); j++) {
                    if (tiles.get(j).kind == TileSpec.Kind.PRECONDITION) { at = j + 1; break; }
                }
            }
            tiles.add(at, TileSpec.core(kind));
            seen.add(kind.coreId);
        }
        return tiles;
    }

    static List<TileSpec> move(List<TileSpec> tiles, int from, int to) {
        List<TileSpec> out = new ArrayList<>(tiles);
        if (from < 0 || from >= out.size() || to < 0 || to >= out.size()) return out;
        out.add(to, out.remove(from));
        return out;
    }

    /** Reorders to match the given ids; unknown ids are ignored and unlisted tiles keep their relative order at the end. */
    static List<TileSpec> reorder(List<TileSpec> tiles, List<String> ids) {
        List<TileSpec> out = new ArrayList<>();
        for (String id : ids) {
            TileSpec tile = find(tiles, id);
            if (tile != null && !out.contains(tile)) out.add(tile);
        }
        for (TileSpec tile : tiles) if (!out.contains(tile)) out.add(tile);
        return out;
    }

    /** Core tiles cannot be removed. */
    static List<TileSpec> remove(List<TileSpec> tiles, String id) {
        List<TileSpec> out = new ArrayList<>(tiles);
        TileSpec tile = find(out, id);
        if (tile != null && !tile.isCore()) out.remove(tile);
        return out;
    }

    static List<TileSpec> rename(List<TileSpec> tiles, String id, String label) {
        List<TileSpec> out = new ArrayList<>(tiles);
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).id.equals(id) && !out.get(i).isCore()) out.set(i, out.get(i).withLabel(label));
        }
        return out;
    }

    /** New shortcuts go just before the Add tile, wherever the user has put it. */
    static List<TileSpec> add(List<TileSpec> tiles, TileSpec shortcut) {
        List<TileSpec> out = new ArrayList<>(tiles);
        if (find(out, shortcut.id) != null) return out;
        int at = out.size();
        for (int i = 0; i < out.size(); i++) if (out.get(i).kind == TileSpec.Kind.ADD) at = i;
        out.add(at, shortcut);
        return out;
    }

    static TileSpec find(List<TileSpec> tiles, String id) {
        for (TileSpec tile : tiles) if (tile.id.equals(id)) return tile;
        return null;
    }

    private static TileSpec parseLine(String line) {
        String[] fields = line.split("\t", -1);
        if (fields.length != 4) return null;
        TileSpec.Kind kind;
        try { kind = TileSpec.Kind.valueOf(fields[0]); }
        catch (IllegalArgumentException e) { return null; }
        if (kind != TileSpec.Kind.SHORTCUT) return TileSpec.core(kind);
        String component = unescape(fields[1]);
        if (component == null || component.indexOf('/') <= 0) return null;
        return TileSpec.shortcut(component, "1".equals(fields[2]), unescape(fields[3]));
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private static String unescape(String value) {
        if (value.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                out.append(next == 't' ? '\t' : next == 'n' ? '\n' : next);
            } else out.append(c);
        }
        return out.toString();
    }

    private TileOrder() { }
}
