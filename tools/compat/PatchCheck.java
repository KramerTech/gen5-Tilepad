import com.tilepad.tweakcore.*;
import java.io.*;
import java.util.*;

/** usage: PatchCheck <system> <recipes dir> <out dir> <recipe>...  Applies each recipe, every consent/nav/popover combination, to the real files; writes the all-on result. */
public final class PatchCheck {
    public static void main(String[] args) throws Exception {
        File system = new File(args[0]), out = new File(args[2]);
        String[] ids = {"consent", "nav", "popover"};
        for (int i = 3; i < args.length; i++) {
            Properties p = Files.properties(new File(args[1], args[i]));
            byte[] stock = Files.read(new File(system, p.getProperty("path").substring("/system/".length())));
            for (int mask = 0; mask < 8; mask++) {
                Config c = new Config(new Properties());
                for (int bit = 0; bit < 3; bit++) c.set(ids[bit], Boolean.toString((mask & 1 << bit) != 0));
                byte[] patched = PatchManager.transform(p, stock, c);
                if (mask == 0 && !Arrays.equals(patched, stock)) throw new AssertionError("mask 0 changed " + args[i]);
                if (mask == 7) { out.mkdirs(); Files.write(new File(out, new File(p.getProperty("path")).getName()), patched); }
            }
            System.out.println("ok " + args[i]);
        }
    }
}
