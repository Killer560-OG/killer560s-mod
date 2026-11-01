import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.shared.RecordCatalog;
import com.killer560.hub.shared.SharedFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One-time combine (shared-stats-1101): folds every Prism instance's old record files into the shared store with the
 * mod's own merge (SharedFile.importLegacy), so a copied file is not counted twice and a re-run adds only what grew.
 * The instance files are read, never changed. ACCOUNT records (run history, best friends...) go to the account that
 * owns most of that instance's account-tagged records (profit trackers), else to {@code defaultAccount}.
 *
 * <p>Usage: java -cp ... CombineInstances <prism instances dir> <shared store dir> <default account uuid, no dashes>
 * Run through tools/shared-records/combine.sh.
 */
public final class CombineInstances {

    public static void main(String[] args) throws Exception {
        Path instances = Path.of(args[0]);
        Path store = Path.of(args[1]);
        String defaultAccount = args[2];
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> s = Files.list(instances)) {
            s.filter(p -> Files.isDirectory(p.resolve("minecraft/config/killer560"))).sorted().forEach(dirs::add);
        }
        Map<Path, String> accounts = new HashMap<>();
        for (Path inst : dirs) {
            accounts.put(inst, dominantAccount(inst.resolve("minecraft/config/killer560"), defaultAccount));
            System.out.println("   " + inst.getFileName() + ": account records go to " + accounts.get(inst));
        }
        for (RecordCatalog.Kind kind : RecordCatalog.ALL) {
            // Newest file first: on a first import the store's values win a conflict of state (which page of Croesus
            // was read last, today's NPC count), so the newest instance's state is the one kept.
            List<Path[]> files = new ArrayList<>();
            for (Path inst : dirs) {
                Path legacy = find(inst.resolve("minecraft/config/killer560"), kind);
                if (legacy != null) {
                    files.add(new Path[]{inst, legacy});
                }
            }
            files.sort((x, y) -> {
                try {
                    return Files.getLastModifiedTime(y[1]).compareTo(Files.getLastModifiedTime(x[1]));
                } catch (Exception e) {
                    return 0;
                }
            });
            if (!files.isEmpty()) {
                System.out.println("== " + kind.sharedName());
            }
            for (Path[] f : files) {
                Path legacy = f[1];
                String account = accounts.get(f[0]);
                Path target = kind.scope() == RecordCatalog.Scope.GLOBAL ? store.resolve(kind.sharedName())
                        : store.resolve("accounts").resolve(account).resolve(kind.sharedName());
                byte[] bytes = Files.readAllBytes(legacy);
                boolean added = new SharedFile(target, kind.policy()).importLegacy(bytes,
                        legacy.toAbsolutePath().normalize().toString());
                JsonObject o = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                System.out.printf("   %-26s %-36s %s%n", f[0].getFileName(), summary(kind, o),
                        added ? "folded in" : "SKIPPED (identical to a file already folded in)");
            }
        }
        System.out.println();
        System.out.println("== Shared store totals: " + store);
        try (Stream<Path> s = Files.walk(store)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                String rel = store.relativize(p).toString().replace('\\', '/');
                if (rel.contains("/.imported") || rel.startsWith(".imported") || rel.endsWith(".lock")) {
                    continue;
                }
                RecordCatalog.Kind kind = RecordCatalog.ALL.stream()
                        .filter(k -> k.sharedName().equals(p.getFileName().toString())).findFirst().orElse(null);
                if (kind == null) {
                    continue;
                }
                JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                System.out.printf("   %-62s %s%n", rel, summary(kind, o));
            }
        }
    }

    static Path find(Path cfg, RecordCatalog.Kind kind) throws Exception {
        try (Stream<Path> s = Files.walk(cfg)) {
            Path hit = s.filter(p -> p.getFileName().toString().equals(kind.legacyName())).findFirst().orElse(null);
            if (hit == null) {
                return null;
            }
            if (kind.legacyInner() != null) {
                hit = hit.resolve(kind.legacyInner());
            }
            return Files.isRegularFile(hit) ? hit : null;
        }
    }

    /** The accountId found most often in the instance's profit tracker records. */
    static String dominantAccount(Path cfg, String fallback) throws Exception {
        Map<String, Integer> n = new HashMap<>();
        for (RecordCatalog.Kind k : List.of(RecordCatalog.CROESUS_LOG, RecordCatalog.EXPERIMENTS, RecordCatalog.BAZAAR_FLIP)) {
            Path p = find(cfg, k);
            if (p == null) {
                continue;
            }
            JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            for (String arr : new String[]{"entries", "sessionLog", "cycles"}) {
                if (o.has(arr)) {
                    for (JsonElement e : o.getAsJsonArray(arr)) {
                        JsonElement id = e.getAsJsonObject().get("accountId");
                        if (id != null && !id.isJsonNull()) {
                            n.merge(id.getAsString().replace("-", ""), 1, Integer::sum);
                        }
                    }
                }
            }
        }
        return n.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(fallback);
    }

    static String summary(RecordCatalog.Kind kind, JsonObject o) {
        switch (kind.sharedName()) {
            case "croesus-log.json": {
                long profit = 0;
                for (JsonElement e : arr(o, "entries")) {
                    profit += e.getAsJsonObject().has("profit") ? e.getAsJsonObject().get("profit").getAsLong() : 0;
                }
                return arr(o, "entries").size() + " claims, profit " + String.format("%,d", profit);
            }
            case "croesus-runs.json":
                return arr(o, "runs").size() + " remembered runs";
            case "rng-item-log.json": {
                int items = 0;
                for (String k : o.keySet()) {
                    items += o.get(k).isJsonObject() ? o.getAsJsonObject(k).size() : 0;
                }
                return o.size() + " meters, " + items + " items";
            }
            case "experiments-profit.json": {
                long sessions = 0;
                double coins = 0;
                for (JsonElement e : arr(o, "buckets")) {
                    JsonObject b = e.getAsJsonObject();
                    sessions += b.has("sessions") ? b.get("sessions").getAsLong() : 0;
                    coins += b.has("rewardValueCoins") ? b.get("rewardValueCoins").getAsDouble() : 0;
                }
                return sessions + " sessions, " + String.format("%,.0f", coins) + " coins";
            }
            case "bazaarflip-profit.json": {
                double delta = 0;
                for (JsonElement e : arr(o, "cycles")) {
                    delta += e.getAsJsonObject().has("purseDelta") ? e.getAsJsonObject().get("purseDelta").getAsDouble() : 0;
                }
                return arr(o, "cycles").size() + " cycles, purse " + String.format("%,.0f", delta);
            }
            case "bazaarflip-npcsold.json":
                return (o.has("day") ? o.get("day").getAsString() : "?") + " " + (o.has("soldCoins") ? o.get("soldCoins") : "0");
            case "run-history.json":
                return arr(o, "runs").size() + " runs";
            case "bestfriends.json": {
                long ms = 0;
                for (JsonElement e : arr(o, "players")) {
                    ms += e.getAsJsonObject().has("totalPartyMs") ? e.getAsJsonObject().get("totalPartyMs").getAsLong() : 0;
                }
                return arr(o, "players").size() + " players, " + (ms / 3_600_000) + " h partied";
            }
            case "eggtracker.json":
                return arr(o, "hunts").size() + " hunts";
            default:
                return "";
        }
    }

    static JsonArray arr(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }
}
