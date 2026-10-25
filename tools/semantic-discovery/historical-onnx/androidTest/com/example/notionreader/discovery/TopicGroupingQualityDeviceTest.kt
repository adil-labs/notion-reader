package com.example.notionreader.discovery

import org.junit.Test

/** Separate quality gate so a benchmark is not required on every corpus iteration. */
class TopicGroupingQualityDeviceTest {
    @Test fun mapperShapedTopicsAndConnectionsMeetHeldOutQuality() {
        ShippingTopicInferenceTest().nativeTopicsAndIndividualConnectionsMeetHeldOutQualityAndKeepFollows()
    }
}
