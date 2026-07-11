package art.arcane.wormholes.door;

public enum PairEndpoint {
    A,
    B;

    public PairEndpoint other() {
        return this == A ? B : A;
    }
}
