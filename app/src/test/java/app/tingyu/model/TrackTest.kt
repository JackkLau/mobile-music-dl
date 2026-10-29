package app.tingyu.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TrackTest {
    @Test fun favoritesAndResolvedVideoReferToSameLocalFile() {
        val favorite = Track("BV1xx411c7mD", 0, "标题")
        assertEquals(favorite.id, favorite.copy(cid = 1234, title = "新标题").id)
    }
    @Test fun differentPartsRemainDistinct() {
        val part = Track("BV1xx411c7mD", 1234, "标题")
        assertNotEquals(part.id, part.copy(page = 2, cid = 5678).id)
    }
}
