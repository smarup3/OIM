package com.mpc

@Grab('log4j:log4j:1.2.17')

import org.apache.log4j.*
import groovy.util.logging.Log4j
import groovy.sql.Sql
import java.security.Key
import java.sql.SQLException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.xml.bind.DatatypeConverter

@Log4j
/**
 * This class will run as a scheduled task in Saviynt to import users from the HR source view to the staging table, which is then read in as a user import
 *
 * @since 07/06/2020
 * @DevelopedBy Wes Vollmar (WZS)
 */
class SapReconGroovy {

    // All configuration, either statically defined (final) or overridden by the property file being loaded
    private static String _run_type = "Incremental"     // Full or Incremental
    private static final String _log_file = "/opt/saviynt/logs/SapRecon.log"
    private static final String _log_level = "DEBUG"
    private static final String _input_properties_file = "/opt/saviynt/mpc_hr_sync/SapRecon.properties"
    private static final String _input_validation_file = "/opt/saviynt/mpc_hr_sync/Validation.groovy"
    private static final String _input_cleanup_file = "/opt/saviynt/mpc_hr_sync/TextScrubber.groovy"
    private static final String _encryption_algorithm = "AES"
    private static final byte[] _encryption_key = "S@v!ynt_s@V!YNt_".getBytes()
    private static String _saviynt_DB_connection = "jdbc:mysql://identity.db.mgroupnet.com:3308/saviyntdb?useUnicode=true&useJDBCCompliantTimezoneShift=true&useLegacyDatetimeCode=false&serverTimezone=UTC"
    private static String _saviynt_DB_user = "appuser"
    private static String _saviynt_DB_password = ""
    private static final String _saviynt_DB_driver = "com.mysql.jdbc.Driver"
    private static String _hold_DB_connection = "jdbc:sqlserver://holding.prod.db.mgroupnet.com:1433;databaseName=IAL"
    private static String _hold_DB_user = "IAL_ADMIN"
    private static String _hold_DB_password = ""
    private static final String _hold_DB_driver = "com.microsoft.sqlserver.jdbc.SQLServerDriver"
    private static final String _hold_source_view_full = "dbo.VW_SAVIYNT_SAP_RECON"
    private static final String _hold_source_view_incremental = "dbo.VW_SAVIYNT_SAP_RECON_INCREMENTAL"
    private static String _hold_source_view = "dbo.VW_SAVIYNT_SAP_RECON_INCREMENTAL"
    private static final String _employee_type = "Employee"
    private static final String _default_supervisor = "admin"
    private static def _days_before_start = 14
    private static final def _milliSecondsInADay = 86400000
    private static final String _dateFormatString = "yyyy-MM-dd HH:mm:ss"
    private static final enum UserAction {None, Update, Insert, Exception}

    /*
    * Method to load users from the HR holding tables to the Saviynt stage table
    *
    * @args - 
    *   [0]: Full/Incremental (default incremental) 
    *   [1]: Days to provision before start (default 14)
    */
    public static void main(Object[] args) {
        // Set logging
        if (_log_level.toUpperCase() == "DEBUG") {
            log.level = Level.DEBUG
        } else {
            log.level = Level.INFO
        }
        log.addAppender(new FileAppender(new PatternLayout("%d{dd-MM-yyyy HH:mm:ss} | %-5p | %m%n"), _log_file))
        log.info("Process initiated")
        log.debug("Parsing arguments")

        // Parse args
        if (args.count() > 0) {
            _run_type = args[0]

            if (_run_type == "Full") {
                _hold_source_view = _hold_source_view_full
            } else {
                _hold_source_view = _hold_source_view_incremental
            }

            _days_before_start = args[1]
        }
        log.info("${_run_type} HR feed initiated")

        // Load properties
        loadPropertyFile()
        log.info("Property file loaded")
        
        // Connect to Saviynt
        def saviyntdbPassword = decrypt(_saviynt_DB_password)
        def saviyntsql = Sql.newInstance(_saviynt_DB_connection,_saviynt_DB_user,saviyntdbPassword,_saviynt_DB_driver)
        saviyntsql.connection.autoCommit = false
        log.info("Created MySQL Connection to Saviynt MySQL Server: ${_saviynt_DB_connection}")

        // Connect to Holding
        def holddbPassword = decrypt(_hold_DB_password)
        def holdsql = Sql.newInstance(_hold_DB_connection,_hold_DB_user,holddbPassword,_hold_DB_driver)
        log.info("Created SQL Connection to Holding SQL Server: ${_hold_DB_connection} ")

        // Define the user query
        String userQuery = """SELECT DISTINCT 
                            sap.LastChanged,
                            sap.Actions_CHANGEDON,
                            sap.Actions_BEGDA,
                            sap.Actions_ENDDA,
                            sap.EmployeeNumber,
                            sap.LegacyEmployeeNumber,
                            sap.ActionType,
                            sap.ActionStatus,
                            sap.InitialHireDate,
                            sap.DetailedStatus,
                            sap.EmployeeStatus,
                            sap.DisplayName,
                            sap.FirstName,
                            sap.LastName,
                            sap.MiddleName,
                            sap.Suffix,
                            sap.NickName,
                            sap.NameFormat,
                            sap.BirthDate,
                            sap.SSN,
                            sap.PersonalCity,
                            sap.PersonalState,
                            sap.CompanyCode,
                            sap.CompanyName,
                            sap.OrgCode,
                            sap.OrgName,
                            sap.Parent_OrgName,
                            sap.PersonnelSubArea,
                            sap.CostCenter,
                            sap.EmployeeGroup,
                            sap.EmployeeSubGroup,
                            sap.PositionCode,
                            sap.PositionName,
                            sap.JobCode,
                            sap.JobName,
                            sap.Chief,
                            sap.UpperManagement,
                            sap.PrimaryLoginId,
                            sap.AlternateLoginId,
                            sap.SAPAccount,
                            sap.EmailAddress,
                            sap.PhoneNumber,
                            sap.PhoneExtension,
                            sap.MobileNumber,
                            sap.PersonnelArea,
                            sap.PersonnelAreaDescription,
                            sap.RoomNumber,
                            sap.Country,
                            sap.Street,
                            sap.City,
                            sap.State,
                            sap.PostalCode,
                            sap.Supervisor_EmployeeNumber,
                            sap.Supervisor_PrimaryLoginId,
                            sap.PrimaryOU,
                            sap.AlternateOU,
                            sap.InternetAccess,
                            sap.MailEnabled,
                            sap.TableName
                            FROM ${_hold_source_view} sap
                            WHERE (NOT(ISNULL(sap.employeegroup,0) = 9 AND ISNULL(sap.employeesubgroup,'AA') = 'UC'))
                            ORDER BY sap.Chief DESC, sap.OrgCode"""

        try {
            // Process user records
            log.info("Executing user query: ${userQuery}")
            holdsql.eachRow(userQuery) { row ->
                log.info("Processing Started for User:${row.PrimaryLoginId}, Employee:${row.EmployeeNumber}, Name:${row.LastName}, ${row.FirstName}")

                String primaryLogin = row.PrimaryLoginId
                Integer employeeNumber = row.EmployeeNumber?.toInteger()
                String lastName = row.LastName
                String firstName= row.FirstName
                String birthdate = row.birthdate
                String ssn = row.ssn
                UserAction userAction = UserAction.None

                if (primaryLogin && primaryLogin != "#Exception#") {
                    // PIC is already populated in holding
                    log.debug("PIC defined as ${primaryLogin} in holding. Searching for match in Staging/Saviynt")

                    // Search for existing Saviynt/Stage record
                    def saviynt_user = saviyntsql.firstRow('SELECT username, employeeid FROM users WHERE username=?', [primaryLogin])
                    def stage_user = saviyntsql.firstRow('SELECT primaryloginid, employeenumber FROM mpc_saviyntstagetable WHERE primaryloginid=?', [primaryLogin])

                    if (!stage_user && !saviynt_user) {
                        // User not in staging table or Saviynt yet, but somehow has a PIC defined
                        log.warning("Did not find a match for user PIC ${primaryLogin} in Saviynt or staging. Inserting as exception for review/cleanup. PIC:${row.PrimaryLoginId}, Employee:${row.EmployeeNumber}, Name:${row.LastName}, ${row.FirstName}")
                        userAction = UserAction.Exception
                    } else if (stage_user && !saviynt_user) {
                        // User is in staging, but not Saviynt. Likely has an exception or has not processed yet for some reason
                        log.debug("Found matching user PIC ${primaryLogin} in staging, but not in Saviynt. User may have a pending exception or is failing to load. Processing update to the record from HR")
                        userAction = UserAction.Update
                    } else if (!stage_user && saviynt_user) {   
                        // User is in Saviynt, but not staging. Validate match for rehire insert and check for contract-to-hire case
                        log.debug("Found matching user PIC ${primaryLogin} in Saviynt, but not in staging")

                        // Confirm match for employee update or contract-to-hire
                        if (saviynt_user.employeeid && employeeNumber == saviynt_user.employeeid.toInteger()) {
                            // Matching employee record in Saviynt, so likely a rehire that just isn't in staging
                            log.debug("Confirmed matching employee PIC in Saviynt. Inserting into staging")
                            userAction = UserAction.Insert
                        } else {
                            // Not an employee record in Saviynt, so verify for contract-to-hire
                            def saviynt_AttributeMatch = saviyntsql.firstRow('SELECT username FROM users WHERE lastname=? AND customproperty1=? AND customproperty2=?', [lastName, ssn, birthdate])?.username

                            if (saviynt_AttributeMatch?.toUpperCase() == primaryLogin.toUpperCase()) {
                                // This is likely a contract-to-hire that has been effectively matched
                                log.debug("Found matching user PIC ${primaryLogin} in Saviynt by LastName, Birthday, and SSN match. Inserting into staging")
                                userAction = UserAction.Insert
                            } else {
                                // This is likely a contract-to-hire that has NOT matched. This is an exception to manually validate
                                log.debug("Did NOT find matching user PIC ${primaryLogin} in Saviynt by EmployeeNumber or LastName, Birthday, and SSN. Entering exception into the staging table")
                                userAction = UserAction.Exception
                            }
                        }
                    } else if (stage_user && saviynt_user) {
                        // User is in both databases
                        log.debug("Found user PIC ${primaryLogin} in both Staging/Saviynt")

                        // Confirm match for employee update
                        if (saviynt_user.employeeid && employeeNumber == saviynt_user.employeeid.toInteger()) {
                            log.debug("Confirmed matching employee PIC in Saviynt. Updating staging data from HR")
                            userAction = UserAction.Update
                        } else if (stage_user.employeenumber && employeeNumber == stage_user.employeenumber.toInteger()) {
                            log.debug("Confirmed matching employee PIC in staging, but has not processed to Saviynt yet. Could be pending exception. Updating staging data from HR")
                            userAction = UserAction.Update
                        } else {
                            log.error("Invalid case for user with mismatched employee number. PIC:${row.PrimaryLoginId}, Name:${row.LastName}, ${row.FirstName}, Holding Employee No:${row.EmployeeNumber}, Saviynt Employee No:${saviynt_user.employeeid}")
                        }
                    }
                } else if (!primaryLogin && employeeNumber) {
                    // PIC is NOT defined in holding.  This is likely a new hire (or recent hire still pending PIC push back to HR)
                    log.debug("No PIC for user ${employeeNumber} in holding. Searching for an existing record or assigning a new PIC")
                    def picSelection = getPic(saviyntsql, holdsql, employeeNumber, firstName, lastName, birthdate, ssn)

                    // Get the results of the PIC assignment
                    primaryLogin = picSelection.picCandidate

                    if (primaryLogin) {
                        // Check for the user in staging
                        def stage_user = saviyntsql.firstRow('SELECT primaryloginid FROM mpc_saviyntstagetable WHERE primaryloginid=?', [primaryLogin])?.primaryloginid
                        
                        if (stage_user) {
                            // User exists in staging already
                            log.debug("Found identified user PIC ${primaryLogin} in staging")

                            if (picSelection.potentialMatch) {
                                // Unlikely issue of a potential match, already existing in staging. This could mean an exception is pending or jobs have not run, while the HR record is also not updated
                                log.warning("Invalid case for user with no PIC in HR, identified with an existing PIC in staging, identified only as a potential match to a Saviynt identity. PIC:${primaryLogin}, Name:${row.LastName}, ${row.FirstName}, Employee No:${row.EmployeeNumber}")
                                userAction = UserAction.None
                            } else if (picSelection.newlyAssigned) {
                                // Unlikely issue of a newly assigned PIC that's already in staging
                                log.warning("Invalid case for user with no PIC in HR, given a new PIC that already exists in staging. PIC:${primaryLogin}, Name:${row.LastName}, ${row.FirstName}, Employee No:${row.EmployeeNumber}")
                                userAction = UserAction.None
                            } else {
                                // Standard update case for a user that just hasn't processed fully back to HR
                                log.debug("Updating existing user record in staging")
                                userAction = UserAction.Update
                            }
                        } else {
                            // User is not in staging. Probably a newly assigned PIC or contract-to-hire without the PIC
                            log.debug("Did not find identified user PIC ${primaryLogin} in staging. Investigate insert as standard or exception")

                            if (picSelection.potentialMatch) {
                                // Potential match not in staging. Insert as an exception for review
                                log.debug("Potential match found in Saviynt. Inserting user PIC: ${primaryLogin} Employee Number: ${employeeNumber} Name: ${lastName}, ${firstName} into staging as an exception")
                                userAction = UserAction.Exception
                            } else if (picSelection.newlyAssigned) {
                                // New PIC assigned, needing insert to staging
                                log.debug("Inserting new user PIC: ${primaryLogin} Employee Number: ${employeeNumber} Name: ${lastName}, ${firstName} into staging")
                                userAction = UserAction.Insert
                            } else {
                                // Unlikely issue of a PIC being referenced that is NOT new, but is somehow missing in staging
                                log.debug("Inserting existing user PIC: ${primaryLogin} Employee Number: ${employeeNumber} Name: ${lastName}, ${firstName} into staging")
                                userAction = UserAction.Insert
                            }
                        }
                    } else {
                        log.error("No PIC identified or reserved for user ${employeeNumber}. No action is being taken at this time")
                    }
                } else {
                    log.error("Invalid case for user with missing data. PIC:${row.PrimaryLoginId}, Employee:${row.EmployeeNumber}, Name:${row.LastName}, ${row.FirstName}")
                }

                // Perform the defined action
                try {
                    log.debug("User account and action has been finalized for ${primaryLogin}. Mapping and validating user attributes")
                    def map = validateAttributes(saviyntsql, holdsql, row, primaryLogin)
                    
                    switch(userAction) {
                        case UserAction.Update:
                            log.debug("Performing update to staging for ${primaryLogin}")
                            updateToStage(saviyntsql, map)
                            break
                        case UserAction.Insert:
                            log.debug("Performing insert to staging for ${primaryLogin}")
                            insertToStage(saviyntsql, map)
                            break
                        case UserAction.Exception:
                            log.debug("Performing insert (with exception) to staging for ${primaryLogin}")
                            insertExceptionRecordToStage(saviyntsql, map)
                            break
                        default:
                            throw new Exception("No action determined for PIC:${primaryLogin}, Employee:${row.EmployeeNumber}, Name:${row.LastName}, ${row.FirstName}")
                    }
                    
                    log.info("Processing Complete for User:${primaryLogin}, Employee:${row.EmployeeNumber}, Name:${row.LastName}, ${row.FirstName}")

                } catch (Exception individual) {
                    log.error("Error processing ${primaryLogin}: ${individual.getMessage()}")
                }
            }
        } catch (SQLException e) {
            saviyntsql.rollback()
            log.error(e.getMessage())
        } catch (Exception ex) {
            log.error(ex.getMessage())
        } finally {
            saviyntsql.close()
            holdsql.close()
        }
    }

    /*
    * Method to load the property file
    */
    static void loadPropertyFile() throws Exception {
        if (!new File(_input_properties_file).exists()) {
            log.error("Failed to find the input property file: ${_input_properties_file}")
        } else {
            Properties props = new Properties()
            FileInputStream fis = new FileInputStream(_input_properties_file)
            props.load(fis)
            fis.close()

            _saviynt_DB_connection = props.getProperty("saviynt_DB_connection")
            _saviynt_DB_user = props.getProperty("saviynt_DB_user")
            _saviynt_DB_password = props.getProperty("saviynt_DB_password")
            _hold_DB_connection = props.getProperty("hold_DB_connection")
            _hold_DB_user = props.getProperty("hold_DB_user")
            _hold_DB_password = props.getProperty("hold_DB_password")
        }
    }

    /*
    * Method to generate/identify a PIC for a given user, which will
    * either reuse the existing PIC or reserve a new PIC from mpc_picsavailable table
    */
    static def getPic(Sql saviyntsql, Sql holdsql, Integer employeeNumber, String firstname, String lastname, String birthdate, String ssn) throws SQLException {
        log.debug("Identifying PIC for ${employeeNumber}")

        String picCandidate 
        boolean newlyAssigned = false
        boolean potentialMatch = false

        // Look in Saviynt for the user by employee number (trusted search)
        if (employeeNumber) {
            log.debug("Searching Saviynt by Employee Number")
            def userByEmpNo = saviyntsql.firstRow('SELECT username FROM users WHERE username NOT LIKE "%@%" AND employeeid=?', [employeeNumber]) 
            if (userByEmpNo) {
                picCandidate = userByEmpNo.username
                log.debug("Found PIC ${picCandidate} in Saviynt by Employee Number = ${employeeNumber}")
            }
        }

        // Look in Saviynt for the user by a match on lastname, birthdate, and SSN (trusted search)
        if (!picCandidate && lastname && birthdate && ssn){
            log.debug("Searching Saviynt by Last Name, Birthdate, and SSN")
            def userByLastBirthSSN = saviyntsql.firstRow('SELECT username FROM users WHERE lastname=? AND customproperty1=? AND customproperty2=?', [lastname, ssn, birthdate])
            if (userByLastBirthSSN) {
                picCandidate = userByLastBirthSSN.username
                log.debug("Found PIC ${picCandidate} in Saviynt by Last Name, Birthdate, and SSN")
            }
        }

        // Look in Saviynt for the user by a match on firstname, lastname, and birthdate (untrusted search)
        if (!picCandidate && firstname && lastname && birthdate) {
            log.debug("Searching Saviynt by First Name, Last Name, and Birthdate")
            def userByFirstLastBirth = saviyntsql.firstRow('SELECT username FROM users WHERE firstname=? AND lastname=? AND customproperty2=?', [firstname, lastname, birthdate])
            if (userByFirstLastBirth) {
                picCandidate = userByFirstLastBirth.username
                log.debug("Found PIC ${picCandidate} in Saviynt by First Name, Last Name, and Birthdate")
                potentialMatch = true
            }
        }

        // Nothing found in Saviynt - get new PIC
        if (!picCandidate) {
            log.debug("No existing PIC found. Reserving a new PIC from mpc_picsavailable")
            picCandidate = saviyntsql.firstRow('SELECT available.pic FROM mpc_picsavailable available INNER JOIN (SELECT pic FROM mpc_picsavailable WHERE reserved = 0 AND datereserved IS NULL ORDER BY rand()) AS selection ON available.pic = selection.pic WHERE NOT EXISTS (select attribute_value from request_access_attrs raa join request_access ra on ra.request_accesskey=raa.request_access_key where raa.attributelable=''User Login'' and ra.status in (1,2) and attribute_value = available.pic COLLATE utf8_general_ci union select username from users where username = available.pic COLLATE utf8_general_ci) limit 1')?.pic
            newlyAssigned = true

            // Reserve the PIC
            reservePic(saviyntsql, picCandidate)
            log.info("PIC ${picCandidate} reserved for employee: ${employeeNumber}")
        }

        [picCandidate:picCandidate, newlyAssigned:newlyAssigned, potentialMatch:potentialMatch]
    }

    /*
    * Method to update the mpc_picsavailable table for the new chosen PIC
    * with reserved=1 and datereserved=[current system date]
    */
    static void reservePic(Sql sql, String pic) throws SQLException {
        log.debug("Updating mpc_picsavailable table to reserve ${pic}")
        def rowcount = sql.executeUpdate('UPDATE mpc_picsavailable SET reserved = 1, datereserved = sysdate() WHERE pic=?', [pic])
        if (rowcount < 1) {
            log.error("Failure reserving PIC code: ${pic}")
            sql.rollback()
        } else {
            sql.commit()
        }
    }

    /*
     * Method to get user attributes from Saviynt
     * like PIC, MUID, SAP Account, start date, end date, and current status
     */
    static def getAttributes(def sql, String userLogin) throws SQLException {
        def results = [:]
        sql.eachRow('SELECT customproperty3, startdate, enddate, CASE WHEN statuskey=1 THEN ''Active'' ELSE ''Inactive'' END AS statuskey FROM users WHERE username=?', [userLogin]) { frow ->
            log.debug("getAttributes() from saviynt where username = ${userLogin}")
            results["muid"] = frow.customproperty3
            results["sap_account"] = frow.customproperty6
            results["start_date"] = frow.startdate
            results["end_date"] = frow.enddate
            results["current_status"] = frow.statuskey
        }
        results
    }

    /*
    * Method to map, validate, and clean user attributes
    */
    static def validateAttributes(Sql saviyntsql, Sql holdsql, def row, String primaryLogin) {
        GroovyClassLoader classLoader = new GroovyClassLoader()
        
        // Load the validation scripts
        if (!new File(_input_validation_file).exists()) {
            log.error("Failed to find the validation file: " + _input_validation_file)
        } 
        Class validationClass = classLoader.parseClass(new File(_input_validation_file))
        def validator = validationClass.newInstance()

        // Load the text cleanup scripts
        if (!new File(_input_cleanup_file).exists()) {
            log.error("Failed to find the validation file: " + _input_cleanup_file)
        } 
        Class textScrubberClass = classLoader.parseClass(new File(_input_cleanup_file))
        def scrubber = textScrubberClass.newInstance()

        // Get the existing user attributes
        Map userAttributes = getAttributes(saviyntsql, primaryLogin)
        def mymap = [:]
        def validationErrors = [:]

        // Map and transform all of the user attributes
        mymap["LastChanged"] = row.LastChanged
        mymap["Actions_CHANGEDON"] = row.Actions_CHANGEDON
        mymap["Actions_BEGDA"] = row.Actions_BEGDA
        mymap["Actions_ENDDA"] = row.Actions_ENDDA
        
        // Calculate the start date
        if (userAttributes?.start_date) {
            mymap["StartDate"] = userAttributes.start_date
        } else {
            java.sql.Date provisioningStartDate = new java.sql.Date(row.actions_begda.getTime() - (_days_before_start * _milliSecondsInADay))
            mymap["StartDate"] = new java.text.SimpleDateFormat(_dateFormatString).format(provisioningStartDate.getTime())
        }

        // Calculate the end date and term date
        if (row.EmployeeStatus == "Active") {
            mymap["EndDate"] = null
            mymap["TerminationDate"] = null
        } else {
            if (userAttributes?.end_date) {
                mymap["EndDate"] = userAttributes.end_date
                mymap["TerminationDate"] = userAttributes.end_date
            } else {
                mymap["EndDate"] = new java.text.SimpleDateFormat(_dateFormatString).format(row.actions_begda.getTime())    // Start of the separation action, as the user is currently disabled
                mymap["TerminationDate"] = new java.text.SimpleDateFormat(_dateFormatString).format(row.actions_begda.getTime())
            }
        }

        mymap["EmployeeNumber"] = row.EmployeeNumber
        mymap["LegacyEmployeeNumber"] = row.LegacyEmployeeNumber

        // Fetch MUID from the mpc_picsavailable table if not set and pushed back to HR yet
        if (userAttributes?.muid) {
            mymap["Muid"]= userAttributes?.muid
        } else {
            def Marathon_Universal_id = saviyntsql.firstRow('SELECT muid FROM mpc_picsavailable WHERE pic=?', [primaryLogin])
            if (Marathon_Universal_id) {
                mymap["Muid"] = Marathon_Universal_id.muid
            }
        }

        mymap["ActionType"] = row.ActionType
        mymap["ActionStatus"] = row.ActionStatus
        mymap["InitialHireDate"] = row.InitialHireDate
        mymap["DetailedStatus"] = row.DetailedStatus
        mymap["EmployeeStatus"] = row.EmployeeStatus
        mymap["EmployeeType"] = _employee_type
        mymap["DisplayName"] = row.DisplayName
        mymap["FirstName"] = row.FirstName
        mymap["LastName"] = row.LastName
        mymap["MiddleName"] = row.MiddleName
        mymap["Suffix"] = row.Suffix
        mymap["NickName"] = row.NickName
        mymap["NameFormat"] = row.NameFormat

        // Validate birthdate
        if (validator.isDobValid(row.birthdate)) {
            mymap["BirthDate"] = row.BirthDate
        } else {
            log.error("${primaryLogin} - Failed Validation for Birthday: ${row.birthdate}")
            validationErrors << [BIRTHDATE: row.birthdate]
        }

        // Validate SSN
        if (validator.isFourValidDigits(row.ssn)) {
            mymap["SSN"] = row.ssn
        } else {
            log.error("${primaryLogin} - Failed Validation for SSN")
            validationErrors << [SSN: row.ssn]
        }

        mymap["PersonalCity"] = row.PersonalCity
        mymap["PersonalState"] = row.PersonalState
        mymap["CompanyCode"] = row.CompanyCode
        mymap["CompanyName"] = row.CompanyName
        mymap["OrgCode"] = row.OrgCode
        mymap["OrgName"] = row.OrgName
        mymap["Parent_OrgName"] = row.Parent_OrgName
        mymap["PersonnelSubArea"] = row.PersonnelSubArea
        mymap["CostCenter"] = row.CostCenter
        mymap["EmployeeGroup"] = row.EmployeeGroup
        mymap["EmployeeSubGroup"] = row.EmployeeSubGroup
        mymap["PositionCode"] = row.PositionCode
        mymap["PositionName"] = row.PositionName
        mymap["JobCode"] = row.JobCode
        mymap["JobName"] = row.JobName
        mymap["Chief"] = row.Chief
        mymap["UpperManagement"] = row.UpperManagement
        mymap["PrimaryLoginId"] = primaryLogin
        mymap["AlternateLoginId"] = row.AlternateLoginId

        // Grab the SAP Account from Saviynt, if its been updated to be different there and not pushed to HR yet
        if (userAttributes?.sap_account) {
            mymap["SAPAccount"] = userAttributes?.sap_account
        } else {
            if (!row.SAPAccount) {
                mymap["SAPAccount"] = primaryLogin
                log.debug("${primaryLogin} - SAP account was defaulted to PIC value of ${primaryLogin}")
            } else {
                mymap["SAPAccount"] = row.SAPAccount
            }
        }

        mymap["EmailAddress"] = row.EmailAddress

        // Validate phone phone number
        def phone = scrubber.cleanPhoneText(row.PhoneNumber)
        if (validator.isTelephoneValid(phone)) {
            mymap["PhoneNumber"] = phone
        } else {
            log.error("${primaryLogin} - Failed Validation for Phone Number: ${row.PhoneNumber}")
            validationErrors << [PHONENUMBER: row.PhoneNumber]
        }

        mymap["PhoneExtension"] = row.PhoneExtension

        // Validate mobile phone number
        def mobile = scrubber.cleanPhoneText(row.MobileNumber)
        if (validator.isMobileValid(mobile)) {
            mymap["MobileNumber"] = mobile
        } else {
            log.error("${primaryLogin} - Failed Validation for Mobile Number: ${row.MobileNumber}")
            validationErrors << [MOBILENUMBER: row.MobileNumber]
        }

        mymap["PersonnelArea"] = row.PersonnelArea
        mymap["PersonnelAreaDescription"] = row.PersonnelAreaDescription
        mymap["RoomNumber"] = row.RoomNumber
        mymap["Country"] = row.Country
        mymap["Street"] = row.Street
        mymap["City"] = row.City
        mymap["State"] = row.State
        mymap["PostalCode"] = row.PostalCode
        mymap["Supervisor_PrimaryLoginId"] = row.Supervisor_PrimaryLoginId ?: _default_supervisor
        mymap["Supervisor_EmployeeNumber"] = row.Supervisor_EmployeeNumber
        mymap["PrimaryOU"] = row.PrimaryOU
        mymap["AlternateOU"] = row.AlternateOU
        mymap["InternetAccess"] = row.InternetAccess
        mymap["MailEnabled"] = row.MailEnabled
        mymap["TableName"] = row.TableName

        mymap
    }

    /*
    * Method to insert user to stage table
    */
    static def insertToStage(def sql, def map) throws SQLException {
        try {
            sql.execute('INSERT INTO mpc_saviyntstagetable(LastChanged,Actions_CHANGEDON,Actions_BEGDA,Actions_ENDDA,StartDate,EndDate,EmployeeNumber,LegacyEmployeeNumber,Muid,ActionType,ActionStatus,InitialHireDate,DetailedStatus,EmployeeStatus,EmployeeType,DisplayName,FirstName,LastName,MiddleName,Suffix,NickName,NameFormat,BirthDate,SSN,PersonalCity,PersonalState,CompanyCode,CompanyName,OrgCode,OrgName,Parent_OrgName,PersonnelSubArea,CostCenter,EmployeeGroup,EmployeeSubGroup,PositionCode,PositionName,JobCode,JobName,Chief,UpperManagement,PrimaryLoginId,AlternateLoginId,SAPAccount,EmailAddress,PhoneNumber,PhoneExtension,MobileNumber,PersonnelArea,PersonnelAreaDescription,RoomNumber,Country,Street,City,State,PostalCode,Supervisor_EmployeeNumber,Supervisor_PrimaryLoginId,PrimaryOU,AlternateOU,InternetAccess,MailEnabled,TableName,createdate,updatedate,terminationdate)VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,sysdate(),sysdate(),?)',[map.LastChanged,map.Actions_CHANGEDON,map.Actions_BEGDA,map.Actions_ENDDA,map.StartDate,map.EndDate,map.EmployeeNumber,map.LegacyEmployeeNumber,map.Muid,map.ActionType,map.ActionStatus,map.InitialHireDate,map.DetailedStatus,map.EmployeeStatus,map.EmployeeType,map.DisplayName,map.FirstName,map.LastName,map.MiddleName,map.Suffix,map.NickName,map.NameFormat,map.BirthDate,map.SSN,map.PersonalCity,map.PersonalState,map.CompanyCode,map.CompanyName,map.OrgCode,map.OrgName,map.Parent_OrgName,map.PersonnelSubArea,map.CostCenter,map.EmployeeGroup,map.EmployeeSubGroup,map.PositionCode,map.PositionName,map.JobCode,map.JobName,map.Chief,map.UpperManagement,map.PrimaryLoginId,map.AlternateLoginId,map.SAPAccount,map.EmailAddress,map.PhoneNumber,map.PhoneExtension,map.MobileNumber,map.PersonnelArea,map.PersonnelAreaDescription,map.RoomNumber,map.Country,map.Street,map.City,map.State,map.PostalCode,map.Supervisor_EmployeeNumber,map.Supervisor_PrimaryLoginId,map.PrimaryOU,map.AlternateOU,map.InternetAccess,map.MailEnabled,map.TableName,map.TerminationDate])
            sql.commit()
        } catch(SQLException ex) {
            sql.rollback()
            log.error(ex.getMessage())
        }
    }

    /*
    * Method to insert an exception user to stage table with an exception flag and comments
    */
    static def insertExceptionRecordToStage(def sql, def map) throws SQLException {
        try {
            sql.execute('INSERT INTO mpc_saviyntstagetable(LastChanged,Actions_CHANGEDON,Actions_BEGDA,Actions_ENDDA,StartDate,EndDate,EmployeeNumber,LegacyEmployeeNumber,Muid,ActionType,ActionStatus,InitialHireDate,DetailedStatus,EmployeeStatus,EmployeeType,DisplayName,FirstName,LastName,MiddleName,Suffix,NickName,NameFormat,BirthDate,SSN,PersonalCity,PersonalState,CompanyCode,CompanyName,OrgCode,OrgName,Parent_OrgName,PersonnelSubArea,CostCenter,EmployeeGroup,EmployeeSubGroup,PositionCode,PositionName,JobCode,JobName,Chief,UpperManagement,PrimaryLoginId,AlternateLoginId,SAPAccount,EmailAddress,PhoneNumber,PhoneExtension,MobileNumber,PersonnelArea,PersonnelAreaDescription,RoomNumber,Country,Street,City,State,PostalCode,Supervisor_EmployeeNumber,Supervisor_PrimaryLoginId,PrimaryOU,AlternateOU,InternetAccess,MailEnabled,TableName,ExceptionFlag,ExceptionDetails,createdate,updatedate,terminationdate)VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,sysdate(),sysdate(),?)',[map.LastChanged,map.Actions_CHANGEDON,map.Actions_BEGDA,map.Actions_ENDDA,map.StartDate,map.EndDate,map.EmployeeNumber,map.LegacyEmployeeNumber,map.Muid,map.ActionType,map.ActionStatus,map.InitialHireDate,map.DetailedStatus,map.EmployeeStatus,map.EmployeeType,map.DisplayName,map.FirstName,map.LastName,map.MiddleName,map.Suffix,map.NickName,map.NameFormat,map.BirthDate,map.SSN,map.PersonalCity,map.PersonalState,map.CompanyCode,map.CompanyName,map.OrgCode,map.OrgName,map.Parent_OrgName,map.PersonnelSubArea,map.CostCenter,map.EmployeeGroup,map.EmployeeSubGroup,map.PositionCode,map.PositionName,map.JobCode,map.JobName,map.Chief,map.UpperManagement,map.PrimaryLoginId,map.AlternateLoginId,map.SAPAccount,map.EmailAddress,map.PhoneNumber,map.PhoneExtension,map.MobileNumber,map.PersonnelArea,map.PersonnelAreaDescription,map.RoomNumber,map.Country,map.Street,map.City,map.State,map.PostalCode,map.Supervisor_EmployeeNumber,map.Supervisor_PrimaryLoginId,map.PrimaryOU,map.AlternateOU,map.InternetAccess,map.MailEnabled,map.TableName,1,'Potential Match Exception',map.TerminationDate])
            sql.commit()
        } catch(SQLException ex) {
            sql.rollback()
            log.error(ex.getMessage())
        }
    }

    /*
    * Method to update user in the stage table
    */
    static def updateToStage(def sql, def map) throws SQLException {
        try {
            def rowcount=sql.executeUpdate('UPDATE mpc_saviyntstagetable SET LastChanged=?,Actions_CHANGEDON=?,Actions_BEGDA=?,Actions_ENDDA=?,StartDate=?,EndDate=?,EmployeeNumber=?,LegacyEmployeeNumber=?,Muid=?,ActionType=?,ActionStatus=?,InitialHireDate=?,DetailedStatus=?,EmployeeStatus=?,EmployeeType=?,DisplayName=?,FirstName=?,LastName=?,MiddleName=?,Suffix=?,NickName=?,NameFormat=?,BirthDate=?,SSN=?,PersonalCity=?,PersonalState=?,CompanyCode=?,CompanyName=?,OrgCode=?,OrgName=?,Parent_OrgName=?,PersonnelSubArea=?,CostCenter=?,EmployeeGroup=?,EmployeeSubGroup=?,PositionCode=?,PositionName=?,JobCode=?,JobName=?,Chief=?,UpperManagement=?,PrimaryLoginId=?,AlternateLoginId=?,SAPAccount=?,EmailAddress=?,PhoneNumber=?,PhoneExtension=?,MobileNumber=?,PersonnelArea=?,PersonnelAreaDescription=?,RoomNumber=?,Country=?,Street=?,City=?,State=?,PostalCode=?,Supervisor_EmployeeNumber=?,Supervisor_PrimaryLoginId=?,PrimaryOU=?,AlternateOU=?,InternetAccess=?,MailEnabled=?,TableName=?,updatedate=sysdate(),terminationDate=? WHERE EmployeeNumber=? AND PrimaryLoginId=?',[map.LastChanged,map.Actions_CHANGEDON,map.Actions_BEGDA,map.Actions_ENDDA,map.StartDate,map.EndDate,map.EmployeeNumber,map.LegacyEmployeeNumber,map.Muid,map.ActionType,map.ActionStatus,map.InitialHireDate,map.DetailedStatus,map.EmployeeStatus,map.EmployeeType,map.DisplayName,map.FirstName,map.LastName,map.MiddleName,map.Suffix,map.NickName,map.NameFormat,map.BirthDate,map.SSN,map.PersonalCity,map.PersonalState,map.CompanyCode,map.CompanyName,map.OrgCode,map.OrgName,map.Parent_OrgName,map.PersonnelSubArea,map.CostCenter,map.EmployeeGroup,map.EmployeeSubGroup,map.PositionCode,map.PositionName,map.JobCode,map.JobName,map.Chief,map.UpperManagement,map.PrimaryLoginId,map.AlternateLoginId,map.SAPAccount,map.EmailAddress,map.PhoneNumber,map.PhoneExtension,map.MobileNumber,map.PersonnelArea,map.PersonnelAreaDescription,map.RoomNumber,map.Country,map.Street,map.City,map.State,map.PostalCode,map.Supervisor_EmployeeNumber,map.Supervisor_PrimaryLoginId,map.PrimaryOU,map.AlternateOU,map.InternetAccess,map.MailEnabled,map.TableName,map.TerminationDate,map.EmployeeNumber,map.PrimaryLoginId])
            if (rowcount>0) {
                sql.commit()
            }
        } catch(SQLException ex) {
            sql.rollback()
            log.error(ex.getMessage())
        }
    }

    /*
    * Method to decrypt database passwords
    */
    static String decrypt(String encryptedData) throws Exception {
        Key key = generateKey()
        Cipher c = Cipher.getInstance(_encryption_algorithm)
        c.init(Cipher.DECRYPT_MODE, key)
        byte[] decordedValue = DatatypeConverter.parseBase64Binary(encryptedData)
        byte[] decValue = c.doFinal(decordedValue)
        return new String(decValue, "UTF-8")
    }

    /*
    * Method to generate an encryption/decryption key
    */
    static Key generateKey() throws Exception {
        return new SecretKeySpec(_encryption_key, _encryption_algorithm)
    }
}