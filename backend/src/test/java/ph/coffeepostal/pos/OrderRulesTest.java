package ph.coffeepostal.pos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ph.coffeepostal.pos.sales.OrderService;

/** Pure rules, no database: discount and VAT maths (centavos). */
class OrderRulesTest {

	@Test
	void noDiscountSplitsVatOutOfTheVatInclusivePrice() {
		var t = OrderService.compute(11200, "NONE");
		assertThat(t.discount()).isZero();
		assertThat(t.total()).isEqualTo(11200);
		assertThat(t.vatable()).isEqualTo(10000);
		assertThat(t.vat()).isEqualTo(1200);
	}

	@Test
	void seniorPwdRemovesVatThenTakesTwentyPercent() {
		var t = OrderService.compute(11200, "SENIOR_PWD");
		// net of VAT = 10,000; 20% of that = 2,000; VAT removed = 1,200; customer pays 8,000
		assertThat(t.discount()).isEqualTo(3200);
		assertThat(t.total()).isEqualTo(8000);
		assertThat(t.vat()).isZero();
		assertThat(t.vatable()).isZero();
	}

	@Test
	void promoTenPercentKeepsVatOnTheDiscountedAmount() {
		var t = OrderService.compute(10000, "PROMO10");
		assertThat(t.discount()).isEqualTo(1000);
		assertThat(t.total()).isEqualTo(9000);
		assertThat(t.vatable() + t.vat()).isEqualTo(9000);
	}

	@Test
	void totalsAlwaysAddUp() {
		for (long gross = 100; gross < 50_000; gross += 137) {
			for (String d : new String[] { "NONE", "SENIOR_PWD", "PROMO10" }) {
				var t = OrderService.compute(gross, d);
				assertThat(t.total()).isEqualTo(gross - t.discount());
				if (!d.equals("SENIOR_PWD")) assertThat(t.vatable() + t.vat()).isEqualTo(t.total());
			}
		}
	}
}
