package vn.motngum.agent

/**
 * Ghép các "ảnh chụp chữ" liên tiếp của cùng một màn hình đơn hàng khi cuộn.
 *
 * Danh sách (RecyclerView) chỉ chứa các dòng đang hiển thị, nên khi cuộn
 * xuống ta nhận được các đoạn chồng lên nhau. Thuật toán:
 *  1. Bỏ phần đầu chung (thanh tiêu đề, mã đơn) và phần cuối chung (nút "Chấp nhận"...).
 *  2. Tìm đoạn chồng lớn nhất giữa cuối phần giữa cũ và đầu phần giữa mới.
 *  3. Nối phần còn lại của bản mới vào.
 * Giữ nguyên các dòng trùng lặp hợp lệ (ví dụ 2 món cùng có "Size M").
 */
object ScreenMerger {

    fun merge(old: List<String>, new: List<String>): List<String> {
        if (old.isEmpty()) return new
        if (new.isEmpty() || containsRun(old, new)) return old

        var head = 0
        while (head < old.size && head < new.size && old[head] == new[head]) head++
        var tail = 0
        while (tail < old.size - head && tail < new.size - head &&
            old[old.size - 1 - tail] == new[new.size - 1 - tail]
        ) tail++

        val oldMid = old.subList(head, old.size - tail)
        val newMid = new.subList(head, new.size - tail)
        if (newMid.isEmpty() || containsRun(oldMid, newMid)) return old

        val maxK = minOf(oldMid.size, newMid.size)
        var overlap = 0
        for (k in maxK downTo 1) {
            if (oldMid.subList(oldMid.size - k, oldMid.size) == newMid.subList(0, k)) {
                overlap = k
                break
            }
        }
        return old.subList(0, head) + oldMid + newMid.subList(overlap, newMid.size) +
            old.subList(old.size - tail, old.size)
    }

    private fun containsRun(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.size > haystack.size) return false
        for (i in 0..haystack.size - needle.size) {
            if (haystack.subList(i, i + needle.size) == needle) return true
        }
        return false
    }
}
