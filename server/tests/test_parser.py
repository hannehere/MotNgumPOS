from motngum.config import DEFAULTS
from motngum.parser import parse_manual, parse_order

GRAB = DEFAULTS["sources"]["com.grab.merchant"]["code_patterns"]

# Mô phỏng chữ đọc từ cây Accessibility của màn hình chi tiết đơn GrabMerchant
SCREEN_A = """
Chi tiết đơn hàng
GF-512
Nguyễn V. A
Giao hàng
3 món
1x
Trà sữa trân châu đường đen
45.000₫
Size L
50% đường
Ít đá
Ghi chú: không lấy ống hút
2x
Trà đào cam sả
70.000₫
Size M
Tạm tính
115.000₫
Chấp nhận
"""

SCREEN_B = """
Đơn hàng mới
GF-88
2x Cà phê sữa đá 58.000đ
- Ít ngọt
1x Bạc xỉu 29.000đ
Ghi chú
Giao nhanh giúp em
Tổng cộng 87.000đ
"""


def test_grab_split_qty_lines():
    o = parse_order(SCREEN_A, GRAB)
    assert o.code == "GF-512"
    assert [(i.qty, i.name) for i in o.items] == [(1, "Trà sữa trân châu đường đen"), (2, "Trà đào cam sả")]
    assert o.items[0].options == ["Size L", "50% đường", "Ít đá"]
    assert o.items[0].note == "không lấy ống hút"
    assert o.items[0].price == ""  # dòng giá riêng bị bỏ qua
    assert o.items[1].options == ["Size M"]
    assert o.cup_count == 3


def test_inline_qty_price_and_note_after_label():
    o = parse_order(SCREEN_B, GRAB)
    assert o.code == "GF-88"
    assert [(i.qty, i.name, i.price) for i in o.items] == [(2, "Cà phê sữa đá", "58.000đ"), (1, "Bạc xỉu", "29.000đ")]
    assert o.items[0].options == ["Ít ngọt"]
    assert o.items[1].note == "Giao nhanh giúp em"


def test_no_code_no_items():
    o = parse_order("Bạn có đơn hàng mới\nMở app để xem", GRAB)
    assert o.code == "" and o.items == []


def test_manual_syntax():
    o = parse_manual("#a12\n2x Trà sữa | Size L, 50% đường | ghi chú: ít ngọt\nCà phê đen x3\nghi chú: mang đi")
    assert o.code == "A12"
    assert o.items[0].qty == 2 and o.items[0].options == ["Size L", "50% đường"] and o.items[0].note == "ít ngọt"
    assert (o.items[1].name, o.items[1].qty) == ("Cà phê đen", 3)
    assert o.note == "mang đi"
