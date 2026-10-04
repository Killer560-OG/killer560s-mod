import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomsim.RoomCaptureRotation;
import com.killer560.hub.roomsim.RoomLibrary;
import com.killer560.hub.roomsim.RoomTileAudit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runs the real RoomCaptureRotation over every shipped capture, outside the game, and prints what it weighed.
 *
 * <pre>
 * tools/layoutsim/rotation.sh -Droomdata=.../rooms-modern.json [-Dverbose=true]
 * </pre>
 *
 * One tab-separated row per room: name, RoomTileAudit's verdict (a CORRUPT room is never placed, so its rotation
 * does not matter until it is re-captured), roof marker, lapis, the rotations the secrets can fit, secret hits per
 * rotation, votes, the answer, and CERTAIN/UNCERTAIN with the reason. Then the lapis-against-marker check the
 * class doc quotes, and the counts.
 */
public final class RotationAudit {
    public static void main(String[] args) throws Exception {
        RoomDatabase.load(Files.readString(Path.of(System.getProperty("roomdata"))));
        Map<String, RoomLibrary.Room> rooms = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try (var files = Files.list(Path.of(args[0]))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                RoomLibrary.Room r = LayoutSim.read(f);
                if (r != null) {
                    rooms.put(r.name, r);
                }
            }
        }
        RoomTileAudit.run(rooms);
        RoomLibrary.ROOMS.putAll(rooms);
        int uncertain = 0;
        int uncertainUsable = 0;
        int lapisWithMarker = 0;
        int lapisAgrees = 0;
        System.out.println("room\taudit\tmarker\tlapis\tallowed\tsecrets\tvotes\tchosen\tverdict");
        for (RoomLibrary.Room r : rooms.values()) {
            RoomCaptureRotation.Verdict v = RoomCaptureRotation.verdict(r);
            if (v.marker().size() == 1 && !v.lapis().isEmpty()) {
                lapisWithMarker++;
                lapisAgrees += v.lapis().contains(v.marker().get(0)) ? 1 : 0;
            }
            if (v.uncertain()) {
                uncertain++;
                uncertainUsable += r.corruptReason == null ? 1 : 0;
            }
            System.out.println(r.name + "\t" + (r.corruptReason == null ? "ok" : "CORRUPT") + "\t" + v.marker()
                    + "\t" + v.lapis() + "\t" + v.allowed() + "\t" + v.secretScores() + " (" + v.secretsTested()
                    + ")\t" + v.voteScores() + "\t" + v.degrees() + "\t"
                    + (v.uncertain() ? "UNCERTAIN: " : "certain: ") + v.reason());
        }
        System.out.println();
        System.out.println("lapis corner present beside a single roof marker: " + lapisWithMarker
                + " rooms, diagonal to it in " + lapisAgrees);
        System.out.println(rooms.size() + " captures, " + uncertain + " uncertain, " + uncertainUsable
                + " of them not already refused by RoomTileAudit");
    }
}
