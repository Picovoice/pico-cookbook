import json
import string
from typing import (
    Any,
    Dict,
    List,
    Sequence
)


def load_helpdesk(path: str) -> Dict[str, Any]:
    with open(path) as f:
        return json.load(f)


def get_ticket_field(helpdesk: Dict[str, Any], key: str) -> Dict[str, Any]:
    for field in helpdesk["ticket_fields"]:
        if field["key"] == key:
            return field
    raise ValueError(f"No ticket field with key `{key}`.")


def get_product_options(helpdesk: Dict[str, Any]) -> List[Dict[str, Any]]:
    return get_ticket_field(helpdesk, "product")["custom_field_options"]


def get_support_tags(helpdesk: Dict[str, Any]) -> List[str]:
    return helpdesk["tags"]


def get_caller(helpdesk: Dict[str, Any]) -> Dict[str, Any]:
    return helpdesk["caller"]


def _normalize(text: str) -> str:
    words = (word.strip(string.punctuation) for word in text.lower().split())
    return " ".join(word for word in words if len(word) > 0)


def _contains_phrase(text: str, phrase: str) -> bool:
    return f" {phrase} " in f" {text} "


def search_help_center(helpdesk: Dict[str, Any], conversation: Sequence[str]) -> List[Dict[str, Any]]:
    articles = {x["id"]: x for x in helpdesk["help_center_articles"]}
    fixtures = helpdesk["search_fixtures"]

    full_text = _normalize(" ".join(conversation))
    latest_text = _normalize(conversation[-1]) if len(conversation) > 0 else ""

    def is_match(fixture: Dict[str, Any]) -> bool:
        product = fixture["match"].get("product")
        terms = fixture["match"].get("terms")

        if terms is not None:
            if not any(_contains_phrase(latest_text, x) for x in terms):
                return False
            return product is None or _contains_phrase(full_text, product)

        return product is not None and _contains_phrase(latest_text, product)

    matched = [x for x in fixtures if "terms" in x["match"] and is_match(x)]
    if len(matched) == 0:
        matched = [x for x in fixtures if "terms" not in x["match"] and is_match(x)]

    result_ids = list(dict.fromkeys(result_id for x in matched for result_id in x["result_ids"]))
    return [articles[x] for x in result_ids]
