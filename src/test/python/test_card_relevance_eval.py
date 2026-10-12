"""Keep the diagnostic's candidate selection faithful to the bootstrap policy."""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "bench"))
import card_relevance_eval as evaluate


def candidate(name, score, kind="behavior"):
    return {"memoryId": name, "semanticScore": score, "kind": kind}


def test_procedure_only_comparison_keeps_a_lower_scoring_procedure():
    trace = {"task": [], "verify": [candidate("unrelated", .90), candidate("tests", .65, "procedure")]}
    assert [x["memoryId"] for x in evaluate.select(trace, .55, "all")] == ["unrelated"]
    assert [x["memoryId"] for x in evaluate.select(trace, .55, "procedure")] == ["tests"]
    assert evaluate.select(trace, .55, "none") == []


def test_floor_abstains_without_top_candidate_override():
    trace = {"task": [candidate("weak", .60)], "verify": []}
    assert evaluate.select(trace, .65, "procedure") == []


def test_test_address_is_kept_while_a_stronger_change_point_is_rejected():
    trace = {"task": [], "verify": [candidate("entry", .90, "change_point"),
                                    candidate("tests", .65, "test_address")]}
    assert [x["memoryId"] for x in evaluate.select(trace, .65, "procedure")] == ["tests"]


def test_combined_cards_are_deduplicated_and_bounded():
    trace = {"task": [candidate("a", .80, "procedure"), candidate("b", .75)],
             "verify": [candidate("a", .80, "procedure"), candidate("c", .75, "procedure"),
                        candidate("d", .74, "procedure")]}
    assert [x["memoryId"] for x in evaluate.select(trace, .55, "procedure")] == ["a", "b", "c"]
