import csv
from pathlib import Path
import pvrhino

def yaml_list(values, indent="      "):
    return "\n".join(f'{indent}- "{value}"' for value in values)


def build_contact_phrases(row):
    phrases = set()

    first = row["first_name"].strip()
    last = row["last_name"].strip()
    nickname = row["nickname"].strip()

    if first:
        phrases.add(first)

    if first and last:
        phrases.add(f"{first} {last}")

    if nickname:
        phrases.add(nickname)

    return phrases


def build_context(
    template_path: str,
    contacts_csv_path: str,
    output_path: str,
):
    contact_values = set()
    company_values = set()

    with open(contacts_csv_path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)

        for row in reader:
            contact_values.update(build_contact_phrases(row))

            company = row["company"].strip()
            if company:
                company_values.add(company)

    contact_values = sorted(contact_values, key=str.lower)
    company_values = sorted(company_values, key=str.lower)

    template = Path(template_path).read_text(encoding="utf-8")

    context = (
        template
        .replace("{{CONTACT_SLOT_VALUES}}", yaml_list(contact_values))
        .replace("{{COMPANY_SLOT_VALUES}}", yaml_list(company_values))
    )

    Path(output_path).write_text(context, encoding="utf-8")

    return output_path


if __name__ == "__main__":
    yaml_path = build_context(
        template_path="../res/context.yml",
        contacts_csv_path="../res/contacts.csv",
        output_path="generated_context.yaml",
    )

    pvrhino.train_context_from_yaml(
        access_key=None,
        yaml_path=str(yaml_path),
        output_path="generated_context.rhn",
        language="en")