package com.mpc;

class Validation {
    boolean isNameTextValid (def name) {
        def pattern = ~/^[A-Za-z\d\-']*/
        def patternMatches = name =~ pattern
        if (name == '' || !name ) {
            true
        } else if (patternMatches.matches()) {
            name.length() <= 80
        } else {
            false
        }
    }
    
    boolean isDisplayNameValid (def displayName) {
        def pattern = ~/^[A-Za-z\d\-' ]*/
        def patternMatches = displayName =~ pattern
        if (!displayName) {
            true
        } else if (patternMatches.matches()) {
            displayName.length() <= 122
        } else {
            false
        }
    }
    
    boolean isTelephoneValid (def phoneNumber) {
        def allowedLength = 50
        isPhoneNumberValid(phoneNumber, allowedLength) 
    }

    boolean isMobileValid (def phoneNumber) {
        def allowedLength = 40
        isPhoneNumberValid(phoneNumber, allowedLength) 
    }    
    
    boolean isPhoneNumberValid (def phoneNumber, def allowedLength) {
        def pattern = ~/^[0-9(\-\) ]*/
        def patternMatches = phoneNumber =~ pattern
        if (!phoneNumber) {
            true
        } else if (patternMatches.matches()) {
            phoneNumber.length() <= allowedLength
        } else {
            false
        }
    }        
    
    boolean isDobValid (def dob) {
        if (!dob) {
            true
        } else if (isFourValidDigits(dob)) {
            boolean isValid = true
            isValid = isValidMonth(dob[0..1] as Integer)
            if (isValid) {
                isValid = isValidMonthDay(dob[2..3] as Integer)
            }
          
            isValid
        } else {
            false
        }
    }
    
    boolean isFourValidDigits (def stringToTest) {
        def pattern = ~/^[0-9]{4}/
        def patternMatches = stringToTest =~ pattern
        patternMatches.matches()
    }
    
    boolean isValidMonth (def monthdigits) {
        monthdigits > 0 && monthdigits <= 12
    }
    
    boolean isValidMonthDay (def daydigits) {
        daydigits > 0 && daydigits <= 31
    }
}