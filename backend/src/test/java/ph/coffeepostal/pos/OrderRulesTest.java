package ph.coffeepostal.pos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ph.coffeepostal.pos.sales.OrderService;

/** Pure rules, no database: discount and VAT maths (centavos). */
class OrderRulesTest {

	@Test
	void noDiscountSplitsVatOutOfTheVatInclusivePrice() {
		var t = OrderService.compute(11200, "NONE", 0, true);
		assertThat(t.discount()).isZero();
		assertThat(t.total()).isEqualTo(11200);
		assertThat(t.vatable()).isEqualTo(10000);
		assertThat(t.vat()).isEqualTo(1200);
	}

	@Test
	void aBranchThatIsNotVatRegisteredShowsNoVat() {
		var t = OrderService.compute(11200, "NONE", 0, false);
		assertThat(t.total()).isEqualTo(11200);
		assertThat(t.vatable()).isZero();
		assertThat(t.vat()).isZero();
	}

	@Test
	void seniorPwdRemovesVatThenTakesTwentyPercent() {
		var t = OrderService.compute(11200, "SENIOR_PWD", 0, true);
		// net of VAT = 10,000; 20% of that = 2,000; VAT removed = 1,200; customer pays 8,000
		assertThat(t.discount()).isEqualTo(3200);
		assertThat(t.total()).isEqualTo(8000);
		assertThat(t.vat()).isZero();
		assertThat(t.vatable()).isZero();
	}

	@Test
	void seniorPwdAtANonVatBranchIsTwentyPercentOfThePrice() {
		var t = OrderService.compute(10000, "SENIOR_PWD", 0, false);
		assertThat(t.discount()).isEqualTo(2000);
		assertThat(t.total()).isEqualTo(8000);
	}

	@Test
	void percentDiscountKeepsVatOnTheDiscountedAmount() {
		var t = OrderService.compute(10000, "PERCENT", 1000, true);   // 10.00%
		assertThat(t.discount()).isEqualTo(1000);
		assertThat(t.total()).isEqualTo(9000);
		assertThat(t.vatable() + t.vat()).isEqualTo(9000);
	}

	@Test
	void percentRoundsHalfUpToAWholeCentavo() {
		// 12.5% of 1,001 = 125.125 -> 125; 12.5% of 1,004 = 125.5 -> 126
		assertThat(OrderService.compute(1001, "PERCENT", 1250, false).discount()).isEqualTo(125);
		assertThat(OrderService.compute(1004, "PERCENT", 1250, false).discount()).isEqualTo(126);
	}

	@Test
	void fixedDiscountNeverGoesBelowZero() {
		assertThat(OrderService.compute(10000, "FIXED", 5000, true).total()).isEqualTo(5000);
		var t = OrderService.compute(3000, "FIXED", 5000, true);
		assertThat(t.discount()).isEqualTo(3000);
		assertThat(t.total()).isZero();
	}

	@Test
	void totalsAlwaysAddUp() {
		for (long gross = 100; gross < 50_000; gross += 137) {
			for (boolean vatReg : new boolean[] { true, false }) {
				for (var d : new Object[][] { { "NONE", 0L }, { "SENIOR_PWD", 0L }, { "PERCENT", 1000L }, { "FIXED", 2500L } }) {
					var t = OrderService.compute(gross, (String) d[0], (Long) d[1], vatReg);
					assertThat(t.total()).isEqualTo(gross - t.discount());
					assertThat(t.total()).isGreaterThanOrEqualTo(0);
					if (vatReg && !d[0].equals("SENIOR_PWD")) assertThat(t.vatable() + t.vat()).isEqualTo(t.total());
				}
			}
		}
	}
}
