package ph.coffeepostal.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import ph.coffeepostal.pos.hr.FaceMatch;
import ph.coffeepostal.pos.hr.FaceMatch.Face;
import ph.coffeepostal.pos.shared.ApiException;

/** The face check on the time clock: plain logic, no database. It only ever flags; it never decides a punch. */
class FaceMatchTest {

	private static double[] face(double seed) {
		double[] d = new double[FaceMatch.SIZE];
		for (int i = 0; i < d.length; i++) d[i] = Math.sin(seed + i) * 0.1;
		return d;
	}

	private static double[] nudged(double[] d, double by) {
		double[] c = d.clone();
		c[0] += by;
		return c;
	}

	@Test
	void sameFaceMatchesAndADifferentOneIsFlaggedForReview() {
		double[] ana = face(1);
		var same = FaceMatch.check(ana, new Face("ok", nudged(ana, 0.05)));
		assertThat(same.status()).isEqualTo("MATCH");
		assertThat(same.score()).isLessThan(FaceMatch.MATCH_MAX);

		var other = FaceMatch.check(ana, new Face("ok", nudged(ana, 0.9)));
		assertThat(other.status()).isEqualTo("REVIEW");
		assertThat(other.score()).isGreaterThan(FaceMatch.MATCH_MAX);
	}

	@Test
	void explainsWhyThereIsNoScore() {
		double[] ana = face(1);
		assertThat(FaceMatch.check(null, new Face("ok", ana)).status()).isEqualTo("NOT_ENROLLED");
		assertThat(FaceMatch.check(ana, null).status()).isEqualTo("NOT_CHECKED");
		assertThat(FaceMatch.check(ana, new Face("off", null)).status()).isEqualTo("NOT_CHECKED");
		assertThat(FaceMatch.check(ana, new Face("none", null)).status()).isEqualTo("NO_FACE");
		assertThat(FaceMatch.check(ana, new Face("multi", null)).status()).isEqualTo("NO_FACE");
		assertThat(FaceMatch.check(ana, new Face("ok", new double[3])).status()).isEqualTo("NO_FACE");   // a reading of the wrong size
		assertThat(FaceMatch.check(ana, new Face("ok", null)).status()).isEqualTo("NO_FACE");
	}

	@Test
	void rejectsReadingsThatCannotBeAFace() {
		assertThat(FaceMatch.valid(face(2))).isTrue();
		assertThat(FaceMatch.valid(new double[FaceMatch.SIZE])).isFalse();   // all zero
		assertThat(FaceMatch.valid(null)).isFalse();
		double[] bad = face(2);
		bad[5] = Double.NaN;
		assertThat(FaceMatch.valid(bad)).isFalse();
		bad[5] = 99;
		assertThat(FaceMatch.valid(bad)).isFalse();
		assertThatThrownBy(() -> FaceMatch.requireValid(new double[10])).isInstanceOf(ApiException.class);
	}

	@Test
	void roundTripsThroughAPostgresArray() {
		double[] ana = face(3);
		double[] back = FaceMatch.parse(FaceMatch.literal(ana));
		assertThat(back).hasSize(FaceMatch.SIZE);
		assertThat(FaceMatch.distance(ana, back)).isLessThan(1e-5);   // stored as a 4-byte real
		assertThat(FaceMatch.parse(null)).isNull();
		assertThat(FaceMatch.parse("{}")).isNull();
	}
}
