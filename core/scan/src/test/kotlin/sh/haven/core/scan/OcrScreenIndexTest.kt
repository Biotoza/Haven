package sh.haven.core.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrScreenIndexTest {

    private val sampleDetections = listOf(
        // Top navigation bar
        OcrLineDetection(OcrBbox(20, 10, 180, 24), "File Edit View", 0.96f),
        // Dialog 1 on left
        OcrLineDetection(OcrBbox(100, 200, 160, 24), "Confirm Delete", 0.95f),
        OcrLineDetection(OcrBbox(110, 250, 60, 24), "Cancel", 0.94f),
        OcrLineDetection(OcrBbox(190, 250, 50, 24), "OK", 0.97f),
        // Dialog 2 on right with identical button labels
        OcrLineDetection(OcrBbox(900, 600, 180, 24), "Network Settings", 0.93f),
        OcrLineDetection(OcrBbox(910, 650, 60, 24), "Cancel", 0.92f),
        OcrLineDetection(OcrBbox(990, 650, 50, 24), "OK", 0.98f),
    )

    private val index = OcrScreenIndex.build(
        detections = sampleDetections,
        frameWidth = 1920,
        frameHeight = 1080,
        frameHash = "test-hash",
        ocrMs = 42.0,
    )

    @Test
    fun `splits multi-word lines into proportional word bounding boxes`() {
        val topLine = index.blocks.first().lines.first()
        assertEquals("File Edit View", topLine.text)
        assertEquals(listOf("File", "Edit", "View"), topLine.words.map { it.text })

        val fileBox = topLine.words[0].bbox
        val editBox = topLine.words[1].bbox
        val viewBox = topLine.words[2].bbox
        assertTrue(fileBox.x < editBox.x && editBox.x < viewBox.x)
        assertEquals(topLine.bbox.y, editBox.y)
        assertEquals(topLine.bbox.height, editBox.height)
    }

    @Test
    fun `queryAt returns containing word first and nearest k-d tree neighbors`() {
        // Point inside "Edit" on the top bar (around x=105, y=22)
        val res = index.queryAt(x = 105.0, y = 22.0, k = 4)
        assertTrue(res.containing.isNotEmpty())
        assertEquals("Edit", res.containing.first().text)
        assertEquals(OcrNode.Level.WORD, res.containing.first().level)

        // Nearest neighbors around Dialog 1's "OK" button at (215, 262)
        val nearOk = index.queryAt(x = 215.0, y = 262.0, k = 3, level = OcrNode.Level.WORD)
        assertEquals("OK", nearOk.nearest.first().node.text)
        assertEquals(0.0, nearOk.nearest.first().rectDistance, 1e-4)
        assertTrue(nearOk.nearest.any { it.node.text == "Cancel" })
    }

    @Test
    fun `findText disambiguates duplicate labels using nearX and nearY anchor`() {
        // Without anchor, top-to-bottom reading order puts Dialog 1's "Cancel" (y=250) first
        val defaultMatches = index.findText("Cancel", exact = true, level = OcrNode.Level.WORD)
        assertEquals(2, defaultMatches.size)
        assertEquals(110, defaultMatches[0].node.bbox.x)
        assertEquals(910, defaultMatches[1].node.bbox.x)

        // With anchor near Dialog 2 (950, 620), Dialog 2's "Cancel" at x=910 ranks first
        val anchoredMatches = index.findText(
            query = "Cancel",
            exact = true,
            nearX = 950.0,
            nearY = 620.0,
            kNeighbors = 3,
            level = OcrNode.Level.WORD,
        )
        assertEquals(2, anchoredMatches.size)
        assertEquals(910, anchoredMatches[0].node.bbox.x)
        // And its k-d tree neighbors include Dialog 2's "OK" button
        assertTrue(anchoredMatches[0].neighbors.any { it.node.text == "OK" && it.node.bbox.x == 990 })
    }

    @Test
    fun `findText matches narrow-kerning joined words when query has spaces`() {
        val idx = OcrScreenIndex.build(
            detections = listOf(
                OcrLineDetection(OcrBbox(2149, 209, 102, 19), "Meetnow", 0.91f),
                OcrLineDetection(OcrBbox(1140, 210, 120, 20), "Pod sharedgoal", 0.93f),
            ),
            frameWidth = 2560,
            frameHeight = 1440,
        )
        val meet = idx.findText("Meet now")
        assertTrue(meet.isNotEmpty())
        assertEquals("Meetnow", meet.first().node.text)

        val goal = idx.findText("Pod shared goal")
        assertTrue(goal.isNotEmpty())
        assertEquals("Pod sharedgoal", goal.first().node.text)
    }
}
