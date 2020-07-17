package com.mpc;

class TextScrubber {
    String cleanPhoneText(def phoneText) {
        if (!phoneText) {
            return null
        }
        List<String> cleanText = []
        phoneText.findAll(/[\S]*/) { textChunk ->
            if (textChunk.length() > 0) cleanText << textChunk
        }
        cleanText.join('-')
    }
  
    String cleanName(def faultyName) {
        if (!faultyName) {
            return null
        }
        String  cleanedName = faultyName.replaceAll(/\./, '')
        cleanedName
    }
}    