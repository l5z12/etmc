package dev.l5z12.etmc.core;

/**
 * A NAT type, as EasyTier's STUN check reports it ({@code common.NatType}), and what it means for
 * reaching other players directly.
 *
 * <p>EasyTier punches through NATs over UDP, and whether it can depends on the NATs at both ends
 * ({@code UdpNatType::get_punch_hole_method} in EasyTier). A cone NAT reaches everyone. A symmetric
 * NAT with predictable ports reaches everyone but a hard symmetric one. A hard symmetric NAT reaches
 * only cone NATs and open addresses. {@link Grade} sorts the types into those three groups.
 *
 * <p>Each type has two descriptions, for two kinds of player: its {@link #label() name} and
 * {@link #mapping()}/{@link #filtering()} behaviour for whoever knows NATs, and its grade's
 * {@link Grade#summary() summary} and {@link Grade#meaning() meaning} in plain words for everyone else.
 *
 * <p>The constants are in EasyTier's order, so each one's ordinal is its protobuf number.
 */
public enum NatType {
    UNKNOWN("Unknown", Grade.UNKNOWN),
    OPEN_INTERNET("OpenInternet", Grade.OPEN),
    NO_PAT("NoPAT", Grade.OPEN),
    FULL_CONE("FullCone", Grade.OPEN),
    RESTRICTED("Restricted", Grade.OPEN),
    PORT_RESTRICTED("PortRestricted", Grade.OPEN),
    SYMMETRIC("Symmetric", Grade.STRICT),
    SYM_UDP_FIREWALL("SymUdpFirewall", Grade.STRICT),
    SYMMETRIC_EASY_INC("SymmetricEasyInc", Grade.MODERATE),
    SYMMETRIC_EASY_DEC("SymmetricEasyDec", Grade.MODERATE);

    /** How easily a NAT type lets other players connect directly. */
    public enum Grade {
        /** Direct connections work with everyone. */
        OPEN,
        /** Direct connections work with everyone but a {@link #STRICT} NAT. */
        MODERATE,
        /** Direct connections work only with an {@link #OPEN} NAT. */
        STRICT,
        UNKNOWN;

        /**
         * Whether EasyTier won't even try to connect this grade and {@code other} directly: both are
         * symmetric, and at least one is strict. {@code false} says only that it will try, not that it
         * will get through, and an unknown grade at either end is {@code false} too.
         */
        public boolean blocksDirect(Grade other) {
            return symmetric() && other != null && other.symmetric() && (this == STRICT || other == STRICT);
        }

        private boolean symmetric() {
            return this == MODERATE || this == STRICT;
        }

        /** What this grade means for playing together, in a few plain words: who you connect to directly. */
        public String summary() {
            return switch (this) {
                case OPEN -> Msg.tr("etmc.nat.summary.open", "Direct connections with everyone");
                case MODERATE -> Msg.tr("etmc.nat.summary.moderate", "Direct connections with most players");
                case STRICT -> Msg.tr("etmc.nat.summary.strict", "Direct connections with some players");
                case UNKNOWN -> Msg.tr("etmc.nat.summary.unknown", "Couldn't check your connection");
            };
        }

        /** The {@link #summary()} explained, in plain words: why, and what happens instead. */
        public String meaning() {
            return switch (this) {
                case OPEN -> Msg.tr("etmc.nat.meaning.open", "Your network doesn't get in the way, so the game "
                        + "can connect straight to any player, with the least lag.");
                case MODERATE -> Msg.tr("etmc.nat.meaning.moderate", "Your network makes direct connections a bit "
                        + "harder. They still work with most players, but if the other player's network makes them hard "
                        + "as well, the game goes through a relay server, which adds some lag.");
                case STRICT -> Msg.tr("etmc.nat.meaning.strict", "Your network makes direct connections hard. They "
                        + "only work with players who get direct connections with everyone. With anyone else, the "
                        + "game goes through a relay server, which adds some lag.");
                case UNKNOWN -> Msg.tr("etmc.nat.meaning.unknown", "The servers etmc asks didn't answer, so your "
                        + "network may block the traffic it needs (UDP). Then every connection goes through a relay "
                        + "server, which adds some lag.");
            };
        }

        /** The NAT types in this grade, by their technical names. */
        public String types() {
            return switch (this) {
                case OPEN -> Msg.tr("etmc.nat.types.open", "No NAT or cone NAT");
                case MODERATE -> Msg.tr("etmc.nat.types.moderate", "Symmetric, predictable ports");
                case STRICT -> Msg.tr("etmc.nat.types.strict", "Symmetric, unpredictable ports");
                case UNKNOWN -> NatType.UNKNOWN.label();
            };
        }
    }

    /** EasyTier's name for this type, as its JSON spells it. */
    private final String easyTierName;
    private final Grade grade;

    NatType(String easyTierName, Grade grade) {
        this.easyTierName = easyTierName;
        this.grade = grade;
    }

    public Grade grade() {
        return grade;
    }

    /** EasyTier's name for this type, such as {@code PortRestricted}, as its own tools show it. */
    public String easyTierName() {
        return easyTierName;
    }

    public boolean known() {
        return this != UNKNOWN;
    }

    /**
     * The type EasyTier reports as {@code value}: its name ({@code "PortRestricted"}), or its number,
     * which older builds wrote. Anything else is {@link #UNKNOWN}.
     */
    public static NatType of(String value) {
        if (value == null) return UNKNOWN;
        String v = value.trim();
        for (NatType t : values()) {
            if (t.easyTierName.equalsIgnoreCase(v) || t.name().equalsIgnoreCase(v)) return t;
        }
        try {
            int n = Integer.parseInt(v);
            return n >= 0 && n < values().length ? values()[n] : UNKNOWN;
        } catch (NumberFormatException e) {
            return UNKNOWN;
        }
    }

    /** Whether EasyTier won't even try to connect this NAT and {@code other} directly (see {@link Grade#blocksDirect}). */
    public boolean blocksDirect(NatType other) {
        return other != null && grade.blocksDirect(other.grade);
    }

    /** The type's technical name, such as "Port-restricted cone". */
    public String label() {
        return switch (this) {
            case UNKNOWN -> Msg.tr("etmc.nat.type.unknown", "Unknown");
            case OPEN_INTERNET -> Msg.tr("etmc.nat.type.open_internet", "No NAT");
            case NO_PAT -> Msg.tr("etmc.nat.type.no_pat", "Full cone, port preserved");
            case FULL_CONE -> Msg.tr("etmc.nat.type.full_cone", "Full cone");
            case RESTRICTED -> Msg.tr("etmc.nat.type.restricted", "Restricted cone");
            case PORT_RESTRICTED -> Msg.tr("etmc.nat.type.port_restricted", "Port-restricted cone");
            case SYMMETRIC -> Msg.tr("etmc.nat.type.symmetric", "Symmetric");
            case SYM_UDP_FIREWALL -> Msg.tr("etmc.nat.type.sym_udp_firewall", "Symmetric UDP firewall");
            case SYMMETRIC_EASY_INC -> Msg.tr("etmc.nat.type.symmetric_easy_inc", "Symmetric, increasing ports");
            case SYMMETRIC_EASY_DEC -> Msg.tr("etmc.nat.type.symmetric_easy_dec", "Symmetric, decreasing ports");
        };
    }

    /**
     * How the NAT picks the public address for a connection, in RFC 4787's terms; {@code null} when
     * unknown. A cone NAT keeps one for every destination, which is what makes it easy to reach.
     */
    public String mapping() {
        return switch (this) {
            case UNKNOWN -> null;
            case OPEN_INTERNET, SYM_UDP_FIREWALL -> Msg.tr("etmc.nat.mapping.none", "None, a public address");
            case NO_PAT, FULL_CONE, RESTRICTED, PORT_RESTRICTED ->
                    Msg.tr("etmc.nat.mapping.independent", "Endpoint-independent");
            case SYMMETRIC, SYMMETRIC_EASY_INC, SYMMETRIC_EASY_DEC ->
                    Msg.tr("etmc.nat.mapping.dependent", "Endpoint-dependent");
        };
    }

    /**
     * Who the NAT lets send back to a mapped address, in RFC 4787's terms; {@code null} when EasyTier's
     * check doesn't tell, which it doesn't for a symmetric NAT.
     */
    public String filtering() {
        return switch (this) {
            case UNKNOWN, SYMMETRIC, SYMMETRIC_EASY_INC, SYMMETRIC_EASY_DEC -> null;
            case OPEN_INTERNET -> Msg.tr("etmc.nat.filtering.none", "None");
            case NO_PAT, FULL_CONE -> Msg.tr("etmc.nat.filtering.independent", "Endpoint-independent");
            case RESTRICTED -> Msg.tr("etmc.nat.filtering.address", "Address-dependent");
            case PORT_RESTRICTED, SYM_UDP_FIREWALL ->
                    Msg.tr("etmc.nat.filtering.address_port", "Address and port-dependent");
        };
    }
}
