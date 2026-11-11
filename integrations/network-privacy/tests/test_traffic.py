"""Traffic volumes are lower bounds built only from monotonic counter intervals."""

import unittest

from plowshare_privacy.traffic import accumulate


class AccumulateTest(unittest.TestCase):
    def test_sums_deltas_between_consecutive_samples(self) -> None:
        self.assertEqual(
            accumulate([(0, 100, 10), (120, 400, 30), (240, 1000, 35)]),
            (3, 900, 25, 0),
        )

    def test_reset_interval_contributes_nothing(self) -> None:
        # The client reconnected between the second and third samples.
        self.assertEqual(
            accumulate([(0, 100, 10), (120, 500, 50), (240, 20, 5), (360, 80, 9)]),
            (4, 460, 44, 1),
        )

    def test_one_counter_going_backwards_drops_the_whole_interval(self) -> None:
        self.assertEqual(accumulate([(0, 100, 50), (120, 300, 10)]), (2, 0, 0, 1))

    def test_single_sample_has_no_volume(self) -> None:
        self.assertEqual(accumulate([(0, 100, 10)]), (1, 0, 0, 0))
        self.assertEqual(accumulate([]), (0, 0, 0, 0))


if __name__ == "__main__":
    unittest.main()
