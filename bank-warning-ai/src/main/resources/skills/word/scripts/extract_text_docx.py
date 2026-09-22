"""Extract all text from a Word document using python-docx.

Usage: uv run python extract_text_docx.py <path-to-docx>
Output: plain text to stdout
"""

import sys
from docx import Document


def main() -> None:
    if len(sys.argv) < 2:
        print("Usage: extract_text_docx.py <docx-path>", file=sys.stderr)
        sys.exit(1)

    path = sys.argv[1]
    doc = Document(path)
    paragraphs = []
    for para in doc.paragraphs:
        text = para.text.strip()
        if text:
            paragraphs.append(text)
    print("\n\n".join(paragraphs))


if __name__ == "__main__":
    main()
