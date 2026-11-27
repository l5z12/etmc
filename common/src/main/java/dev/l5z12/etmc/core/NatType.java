package dev.l5z12.etmc.core;

/**
 * A UDP NAT type, as EasyTier's STUN check reports it ({@code common.NatType}), and what it means for
 * reaching other players directly.
 *
 * <p>EasyTier punches through NATs over UDP, and whether it can depends on the NATs at both ends
 * ({@code UdpNatType::get_punch_hole_method} in EasyTier). A cone NAT reaches everyone. A symmetric
 * NAT with predictable ports reaches everyone but a hard symmetric one. A hard symmetric NAT reaches
 * only cone NATs and open addresses. {@link Grade} sorts the types into those three groups.
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

        public String label() {
            return switch (this) {
                case OPEN -> Msg.tr("etmc.nat.grade.open", "Open");
                case MODERATE -> Msg.tr("etmc.nat.grade.moderate", "Moderate");
                case STRICT -> Msg.tr("etmc.nat.grade.strict", "Strict");
                case UNKNOWN -> Msg.tr("etmc.nat.grade.unknown", "Unknown");
            };
        }

        /** What this grade means for playing together, as a sentence. */
        public String meaning() {
            return switch (this) {
                case OPEN -> Msg.tr("etmc.nat.meaning.open", "Direct connections should work with everyone.");
                case MODERATE -> Msg.tr("etmc.nat.meaning.moderate",
                        "Direct connections work with most players, but not with a strict NAT.");
                case STRICT -> Msg.tr("etmc.nat.meaning.strict", "Direct connections only work with players "
                        + "on an open NAT. With anyone else, the game goes through the relay.");
                case UNKNOWN -> Msg.tr("etmc.nat.meaning.unknown", "etmc couldn't find out, because the servers "
                        + "it asks didn't answer. This network may block UDP, and then every connection goes "
                        + "through the relay.");
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

    /** The type in plain words, such as "port-restricted cone". */
    public String label() {
        return switch (this) {
            case UNKNOWN -> Msg.tr("etmc.nat.type.unknown", "unknown");
            case OPEN_INTERNET -> Msg.tr("etmc.nat.type.open_internet", "no NAT");
            case NO_PAT -> Msg.tr("etmc.nat.type.no_pat", "NAT that keeps ports");
            case FULL_CONE -> Msg.tr("etmc.nat.type.full_cone", "full cone");
            case RESTRICTED -> Msg.tr("etmc.nat.type.restricted", "restricted cone");
            case PORT_RESTRICTED -> Msg.tr("etmc.nat.type.port_restricted", "port-restricted cone");
            case SYMMETRIC -> Msg.tr("etmc.nat.type.symmetric", "symmetric");
            case SYM_UDP_FIREWALL -> Msg.tr("etmc.nat.type.sym_udp_firewall", "symmetric firewall");
            case SYMMETRIC_EASY_INC, SYMMETRIC_EASY_DEC ->
                    Msg.tr("etmc.nat.type.symmetric_easy", "symmetric, predictable ports");
        };
    }

    /** The grade with the type after it, such as "Open (port-restricted cone)"; just "Unknown" when unknown. */
    public String describe() {
        if (!known()) return grade.label();
        return Msg.tr("etmc.nat.describe", "%s (%s)", grade.label(), label());
    }
}
