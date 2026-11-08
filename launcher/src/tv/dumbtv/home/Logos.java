package tv.dumbtv.home;

/** On-air marks for the Live TV channels, shown next to the name. */
final class Logos {
    private Logos() {}

    static int of(Channels.Channel ch) {
        switch (ch.name) {
            case "BBC One": return R.drawable.channel_bbc_one;
            case "BBC Two": return R.drawable.channel_bbc_two;
            case "BBC Three": return R.drawable.channel_bbc_three;
            case "BBC Four": return R.drawable.channel_bbc_four;
            case "BBC Alba": return R.drawable.channel_bbc_alba;
            case "BBC Scotland": return R.drawable.channel_bbc_scotland;
            case "BBC News": return R.drawable.channel_bbc_news;
            case "BBC Parliament": return R.drawable.channel_bbc_parliament;
            case "CBBC": return R.drawable.channel_cbbc;
            case "CBeebies": return R.drawable.channel_cbeebies;
            case "ITV1": return R.drawable.channel_itv1;
            case "ITV2": return R.drawable.channel_itv2;
            case "ITV3": return R.drawable.channel_itv3;
            case "ITV4": return R.drawable.channel_itv4;
            case "ITV Quiz": return R.drawable.channel_itv_quiz;
            case "Channel 4": return R.drawable.channel_4;
            case "E4": return R.drawable.channel_e4;
            case "Film4": return R.drawable.channel_film4;
            case "More4": return R.drawable.channel_more4;
            case "4seven": return R.drawable.channel_4seven;
            case "S4C": return R.drawable.channel_s4c;
            case "Channel 5": return R.drawable.channel_5;
            case "5USA": return R.drawable.channel_5usa;
            case "5Star": return R.drawable.channel_5star;
            case "5Action": return R.drawable.channel_5action;
            case "Sky Witness": return R.drawable.channel_sky_witness;
            case "Sky Atlantic": return R.drawable.channel_sky_atlantic;
            case "Sky Comedy": return R.drawable.channel_sky_comedy;
            case "Sky Documentaries": return R.drawable.channel_sky_documentaries;
            case "Sky Crime": return R.drawable.channel_sky_crime;
            case "Sky Arts": return R.drawable.channel_sky_arts;
            case "Sky Nature": return R.drawable.channel_sky_nature;
            case "Sky Sci-Fi": return R.drawable.channel_sky_sci_fi;
            case "Sky History": return R.drawable.channel_sky_history;
            case "Sky Mix": return R.drawable.channel_sky_mix;
            case "Sky Kids": return R.drawable.channel_sky_kids;
            case "Sky Cinema Premiere": return R.drawable.channel_sky_cinema_premiere;
            case "Sky Cinema Family": return R.drawable.channel_sky_cinema_family;
            case "Sky Cinema Action": return R.drawable.channel_sky_cinema_action;
            case "Sky Cinema Greats": return R.drawable.channel_sky_cinema_greats;
            case "Sky Cinema Comedy": return R.drawable.channel_sky_cinema_comedy;
            case "Sky Cinema Thriller": return R.drawable.channel_sky_cinema_thriller;
            case "Sky Cinema Drama": return R.drawable.channel_sky_cinema_drama;
            case "Sky Cinema Sci-Fi & Horror": return R.drawable.channel_sky_cinema_sci_fi_horror;
            case "Sky Sports Main Event": return R.drawable.channel_sky_sports_main_event;
            case "Sky Sports Premier League": return R.drawable.channel_sky_sports_premier_league;
            case "Sky Sports Football": return R.drawable.channel_sky_sports_football;
            case "Sky Sports+": return R.drawable.channel_sky_sports_plus;
            case "Sky Sports Cricket": return R.drawable.channel_sky_sports_cricket;
            case "Sky Sports Golf": return R.drawable.channel_sky_sports_golf;
            case "Sky Sports F1": return R.drawable.channel_sky_sports_f1;
            case "Sky Sports Tennis": return R.drawable.channel_sky_sports_tennis;
            case "Sky Sports News": return R.drawable.channel_sky_sports_news;
            case "Sky Sports NFL": return R.drawable.channel_sky_sports_nfl;
            case "Sky Sports Racing": return R.drawable.channel_sky_sports_racing;
            case "Sky Sports Mix": return R.drawable.channel_sky_sports_mix;
            case "Sky News": return R.drawable.channel_sky_news;
            default: return 0;
        }
    }
}
