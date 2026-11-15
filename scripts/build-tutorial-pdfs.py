"""Build the bundled tutorial guides. Requires Python 3 and reportlab.

Usage: python scripts/build-tutorial-pdfs.py [--font path/to/CJK-font.ttf]
The default Windows SimSun font embeds Latin and Chinese glyphs in both PDFs.
"""
import argparse
import html
import re
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak,
)

ROOT = Path(__file__).resolve().parents[1]
GUIDES = ROOT / 'docs' / 'tutorials'
ACCENT = colors.HexColor('#0078d4')


def inline(text):
    text = html.escape(text)
    text = re.sub(r'\[([^\]]+)\]\(([^)]+)\)',
                  lambda m: '<link href="%s" color="#0078d4">%s</link>' % (
                      m[2].replace('README.zh-CN.md', 'tutorials.zh-CN.pdf')
                      .replace('README.md', 'tutorials.en.pdf'), m[1]), text)
    text = re.sub(r'`([^`]+)`', r'<font color="#333333">\1</font>', text)
    text = re.sub(r'\*\*([^*]+)\*\*', r'<b>\1</b>', text)
    return text


def build(source, output, chinese):
    styles = {
        'body': ParagraphStyle('body', fontName='Guide', fontSize=9.5,
                               leading=15, spaceAfter=7, wordWrap='CJK'),
        'cell': ParagraphStyle('cell', fontName='Guide', fontSize=8.5,
                               leading=13, wordWrap='CJK'),
    }
    for level, size in [(1, 23), (2, 16), (3, 12)]:
        styles[level] = ParagraphStyle(str(level), parent=styles['body'],
                                      fontSize=size, leading=size * 1.35,
                                      spaceBefore=12, spaceAfter=10,
                                      textColor=ACCENT, keepWithNext=True)
    doc = SimpleDocTemplate(str(output), pagesize=A4, leftMargin=45,
                            rightMargin=45, topMargin=48, bottomMargin=45,
                            title='SQL Diagnostic Workbench - Tutorials' + (' (中文)' if chinese else ' (English)'),
                            author='Bitdata')
    story = []
    lines = source.read_text(encoding='utf-8').splitlines()
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        if not line:
            i += 1
            continue
        if line.startswith('|'):
            rows = []
            while i < len(lines) and lines[i].strip().startswith('|'):
                cells = [c.strip() for c in lines[i].strip().strip('|').split('|')]
                if not all(re.fullmatch(r':?-+:?', c) for c in cells):
                    rows.append(cells)
                i += 1
            count = len(rows[0])
            if any(len(row) != count for row in rows):
                raise ValueError('Inconsistent table columns: ' + str(rows))
            if count == 3 and rows[0][0] in {'Step', '步骤'}:
                widths = [38, doc.width * .49, doc.width * .51 - 38]
            else:
                widths = [doc.width / count] * count
            table = Table([[Paragraph(inline(c), styles['cell']) for c in row] for row in rows],
                          colWidths=widths, repeatRows=1, hAlign='LEFT')
            table.setStyle(TableStyle([
                ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#e9f2f9')),
                ('ROWBACKGROUNDS', (0, 1), (-1, -1), [colors.white, colors.HexColor('#f6f6f6')]),
                ('VALIGN', (0, 0), (-1, -1), 'TOP'),
                ('LINEBELOW', (0, 0), (-1, 0), .6, ACCENT),
                ('LINEBELOW', (0, 1), (-1, -1), .3, colors.HexColor('#dddddd')),
                ('LEFTPADDING', (0, 0), (-1, -1), 6),
                ('RIGHTPADDING', (0, 0), (-1, -1), 6),
                ('TOPPADDING', (0, 0), (-1, -1), 6),
                ('BOTTOMPADDING', (0, 0), (-1, -1), 6),
            ]))
            story.extend([table, Spacer(1, 10)])
            continue
        heading = re.match(r'^(#{1,3}) (.+)$', line)
        if heading:
            level, title = len(heading[1]), heading[2]
            story.append(Paragraph(inline(title), styles[level]))
            i += 1
            continue
        para = [line]
        i += 1
        # Preserve ordered and unordered list items as separate paragraphs.
        if not re.match(r'^(\d+\. |[-*] )', line):
            while i < len(lines) and lines[i].strip() and not re.match(r'^(#|\||\d+\. |[-*] )', lines[i]):
                para.append(lines[i].strip())
                i += 1
        story.append(Paragraph(inline(' '.join(para)), styles['body']))

    def page(canvas, document):
        canvas.saveState()
        canvas.setFont('Guide', 8)
        canvas.setFillColor(colors.HexColor('#666666'))
        canvas.drawString(45, A4[1] - 30, 'SQL Diagnostic Workbench | Tutorials')
        canvas.drawRightString(A4[0] - 45, 25, str(document.page))
        canvas.restoreState()

    doc.build(story, onFirstPage=page, onLaterPages=page)
    print(output)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--font', default='C:/Windows/Fonts/simsun.ttc')
    args = parser.parse_args()
    pdfmetrics.registerFont(TTFont('Guide', args.font))
    pdfmetrics.registerFontFamily('Guide', normal='Guide', bold='Guide', italic='Guide', boldItalic='Guide')
    build(GUIDES / 'README.md', GUIDES / 'tutorials.en.pdf', False)
    build(GUIDES / 'README.zh-CN.md', GUIDES / 'tutorials.zh-CN.pdf', True)
