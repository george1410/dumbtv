package tv.dumbtv.home;

/** Host-side checks for EPGTalk title cleanup, without a device. */
public final class EpgTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    private static void eq(String got, String want) {
        check(want.equals(got), want + " ← " + got);
    }

    public static void main(String[] args) {
        eq(Epg.plain("The Bidding Room"), "The Bidding Room");
        eq(Epg.plain("S.W.A.T."), "S.W.A.T.");
        eq(Epg.plain("America, Inc."), "America, Inc.");
        eq(Epg.plain("The Man From U.N.C.L.E."), "The Man From U.N.C.L.E.");
        eq(Epg.plain("Was wäre, wenn..."), "Was wäre, wenn...");
        eq(Epg.plain("Re:"), "Re:");
        eq(Epg.plain(""), "");
        eq(Epg.plain("  Pointless  "), "Pointless");

        eq(Epg.plain("🎾 [Tennis] Tennis Racquet, Metal Teeth Art, Cinema Seats"),
                "Tennis Racquet, Metal Teeth Art, Cinema Seats");
        eq(Epg.plain("⚽ [EPL] Premier League"), "Premier League");
        eq(Epg.plain("⚽ [EPL] Chelsea v ⚽ [EPL] Manchester United"),
                "Chelsea v Manchester United");
        eq(Epg.plain("Falkirk v ⚽ [SPL] Celtic"), "Falkirk v Celtic");
        eq(Epg.plain("[LIVE]: EFL Championship"), "EFL Championship");
        eq(Epg.plain("[LIVE] 🎾 [Tennis] Tennis: ATP Paris Masters 2026"),
                "Tennis: ATP Paris Masters 2026");
        eq(Epg.plain("Mexico City 🏎️ [F1] Grand Prix: [QUALY]"), "Mexico City Grand Prix");
        eq(Epg.plain("Jamie Chadwick Realises an 🏎️ [F1] F1 Dream"),
                "Jamie Chadwick Realises an F1 Dream");
        eq(Epg.plain("Live: UEFA ⚽🏆 [UCL] Champions League Goals Show Xtra"),
                "Live: UEFA Champions League Goals Show Xtra");
        eq(Epg.plain("Manchester City v Brighton & Hove Albion"),
                "Manchester City v Brighton & Hove Albion");

        System.out.println("EpgTest passed");
    }
}
