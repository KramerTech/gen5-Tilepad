package com.tilepad;

import com.tilepad.tweakcore.AmbientPolicy;
import org.junit.Test;
import static org.junit.Assert.*;

public class AmbientSnapshotTest {
    @Test public void parsesActivePausedAndOffSnapshots() {
        AmbientSnapshot active = AmbientSnapshot.parse(
            "state=active\nmode=daynight\ncolorHex=#FF7D73\nlevel=4\nheadline=Day and Night\n"
            + "detail=The car's Night theme is active, so your Night color is on.\ndayNight=Night\nequipment=auto\n");
        assertEquals(AmbientSnapshot.State.ACTIVE, active.state);
        assertEquals("Active", active.pill());
        assertEquals("Day and Night", active.tileHeadline());
        assertTrue(active.tileDetail().contains("Night"));
        assertTrue(active.tileDetail().contains("Lv 4"));

        AmbientSnapshot paused = AmbientSnapshot.parse(
            "state=paused\nmode=music\ncolorHex=#00B4FF\nlevel=6\nheadline=Paused · Park only\n"
            + "detail=Paused while driving\n");
        assertEquals(AmbientSnapshot.State.PAUSED, paused.state);
        assertEquals("Paused", paused.pill());
        assertEquals("Paused", paused.tileHeadline());
        assertEquals("In Drive · Park only", paused.tileDetail());

        AmbientSnapshot off = AmbientSnapshot.parse("");
        assertEquals(AmbientSnapshot.State.OFF, off.state);
        assertEquals("Off", off.pill());
        assertEquals("Owner", off.tileHeadline());
        assertEquals("Car default", off.tileDetail());
        assertEquals(PreconPresenter.Tone.MUTED, off.tone());
        assertEquals(PreconPresenter.Tone.GOOD, active.tone());
        assertEquals(PreconPresenter.Tone.WARN, paused.tone());
        assertEquals("off", AmbientPolicy.Mode.OFF.id);
    }
}
