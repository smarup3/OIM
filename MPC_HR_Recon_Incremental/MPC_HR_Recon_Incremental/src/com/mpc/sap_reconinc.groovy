package com.mpc
import groovy.sql.Sql
import groovy.util.logging.Log4j
//import java.sql.DriverManager

@Grab('log4j:log4j:1.2.17')

import org.apache.log4j.*
//import groovy.util.logging.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.security.Key
import javax.xml.bind.DatatypeConverter
import java.sql.SQLException


@Log4j
/**
 * This class will be used to create a scheduled task in Saviynt to import users from
 * HR source View.
 * Incremental import
 * @since 19 Sep 2019
 * @DevelopedBy Simeio Solutions Ltd
 * @DevelopedFor MPC
 *
 */
class SapRecon{

    private static final String ALGO = "AES"
    private static byte[] keyValue = "S@v!ynt_s@V!YNt_".getBytes()
    public static String saviynt_DB_password = ""
    public static String hold_DB_password = ""


    /**
     Method to load individual user to saviynt stage table
     @param primaryLogin Userlogin for the user
     */
    public static void main(String[] args) {
        def fileLocation='/opt/saviynt/logs/Saprecon.log'
        
        // Need to set log level
        // Logger log = Logger.getLogger(SapRecon.class.getName())
        log.level = Level.DEBUG
        // add an appender to log to file
        log.addAppender(new FileAppender(new PatternLayout('%d{dd-MM-yyyy HH:mm:ss -} [%t] %5p %c{2} - %m%n'), fileLocation))

        //********Load propertyfile********
        log.info("MPC:trusted_reconciliation.groovy:Loading property file")
        loadPropertyFile()
        //**************************************************
        //************ Connecting to Saviynt database ****************
        //**************************************************
         def saviyntdbURL = 'jdbc:mysql://identity.db.mgroupnet.com:3308/saviyntdb?useUnicode=true&useJDBCCompliantTimezoneShift=true&useLegacyDatetimeCode=false&serverTimezone=UTC'
        def saviyntdbUserName = 'appuser'
        def saviyntdbPassword =decrypt(saviynt_DB_password)
        def saviyntdbDriver = 'com.mysql.jdbc.Driver'
        def saviyntsql= Sql.newInstance(saviyntdbURL,saviyntdbUserName,saviyntdbPassword,saviyntdbDriver)
        saviyntsql.connection.autoCommit = false
        log.info("MPC:trusted_reconciliation.groovy: Created SQL Connection to Saviynt MySQL Server ${saviyntdbURL} ")

        //**************************************************
        //************ Connecting to Hold database ****************
        //**************************************************

        def holddbURL = 'jdbc:sqlserver://holding.prod.db.mgroupnet.com:1433;databaseName=IAL'
        def holddbUserName = 'IAL_ADMIN'
        def holddbPassword = decrypt(hold_DB_password)
        def holddbDriver = 'com.microsoft.sqlserver.jdbc.SQLServerDriver'
        println "Connected to database"
        def holdsql = Sql.newInstance(holddbURL,holddbUserName,holddbPassword,holddbDriver)
        log.info("MPC:trusted_reconciliation.groovy: Created SQL Connection to Hold database...${holddbURL} ")

        //****Fetching records from view*********
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
FROM dbo.VW_SAVIYNT_SAP_RECON_INCREMENTAL sap
WHERE 
( NOT ( ISNULL(employeegroup,0) = 9 AND ISNULL(employeesubgroup,'AA') = 'UC') )
ORDER BY sap.Chief DESC, sap.OrgCode"""


        //*********Processing each user record ********
        log.info("MPC:trusted_reconciliation.groovy:Main: Execute Query ${userQuery}")
        //println "Println [Execute Query] run sql: ${userQuery}"
        holdsql.eachRow(userQuery) { row ->
            boolean userExists = false
            // boolean isUpdate=false
            boolean isExceptional=false
            log.info("MPC:trusted_reconciliation.groovy:Main Create User for... ")
            log.info("User:${row.PrimaryLoginId}  , Employee:${ row.EmployeeNumber}, Name:${row.firstname},${row.lastname}........")

            String primaryLogin = row.PrimaryLoginId
            //def map=validateAttributes(sql,row,primaryLogin)
            Integer employeeNumber = row.EmployeeNumber?.toInteger()
            String lastName = row.LastName
            String birthdate = row.birthdate
            String ssn = row.ssn
            String firstname= row.FirstName

            if((primaryLogin!=null && primaryLogin!='#Exception#')&&employeeNumber) {
                log.info("Entering pic exist block as PrimaryLoginID:${primaryLogin} found for the user record")
                def EMPNO = saviyntsql.firstRow('SELECT employeeid FROM users WHERE username = ?',[primaryLogin])?.employeeid
                log.info("Finding Employeenumber for ${primaryLogin} EmployeeNumber:${} found")
                if(!EMPNO&&lastName&&birthdate&&ssn){
                    def users=saviyntsql.firstRow('select username from users where lastname=? and customproperty1=? and customproperty2=?',[lastName,ssn,birthdate])?.username
                    //  println "-----------------${users}-------"
                    if(users==primaryLogin){
                        log.info( "------------Entering contract to hire, as user record matching lastname, birthdate, ssn and username is found in saviynt---------")
                        def picCandidate=users
                        log.info("PICGENPICGEN: Found PIC ${picCandidate} in saviynt by Lastname, Birthday, SSN")
                        def map=validateAttributes(saviyntsql,holdsql,row,picCandidate)
                        log.debug("Inserting user record having primaryLoginID ${users} to stage table")
                        InsertToStage(saviyntsql,map)
                    }else{
                        log.info("-------Entering to Exception convertion to hire as the PIC ${primaryLogin} from source doesnot match with username ${users} in saviynt ---------------")
                        //def user_login= saviyntsql.firstRow('select username from users where lastname=? and customproperty1=? and customproperty2=?',[lastName,ssn,birthdate])?.username
                        def map=validateAttributes(saviyntsql,holdsql,row,primaryLogin)
                        log.debug("Inserting user record ${primaryLogin} as exception record to stage table")
                        InsertExceptionalToStage(saviyntsql,map)
                    }
                }else {
                    userExists = true
                    println "****User Exists****"
                    log.info("Main:This user already exists (has primary_login_id)... ")
                    log.info("Main:Update user entry:${row.EmployeeNumber} on stage table ,as user PIC:${row.PrimaryLoginId} found")
                    def map=validateAttributes(saviyntsql,holdsql,row,primaryLogin)
                    //def rowcount = saviyntsql.executeUpdate("update mpc_saviynt_stage_table set USR_FIRST_NAME=?,USR_LAST_NAME=?,USR_MIDDLE_NAME=?,USR_TELEPhoneNumber=?,USR_MOBILE=?,USR_UDF_USR_UDF_SSN2=?,USR_UDF_USR_UDF_BIRTHDAY2=?,USR_EMAIL=?,USR_LOGIN=?,USR_OrgName=?,USR_HOME_POSTAL_ADDRESS=?,USR_POSTAL_ADDRESS=?,USR_POSTAL_CODE=?,USR_STREET=?,USR_STATE=?,USR_COUNTRY=?,USR_DEPT_NO=?,USR_EMP_NO=?,USR_LOCATION=?,USR_UDF_SAP_PERSONNELAREA=?,USR_UDF_Chief=?,USR_EMP_TYPE=?,USR_JobCode=?,USR_UDF_MARATHON_UNIVERSAL_ID=?,USR_UDF_SAP_COMPANY_FULL_NAME=?,USR_UDF_SAP_COMPANY_CODE=?,USR_UDF_SAP_ActionType=?,USR_UDF_NICKNAME=?,USR_UDF_PHONE_EXTENSION=?,USR_UDF_ALT_PhoneNumber=?,USR_UDF_SAP_BUILDING_NUMBER=?,USR_UDF_SAP_ROOM_NUMBER=?,USR_UDF_SAP_LEGACY_COMP_CODE=?,USR_UDF_SAP_COMPANY_ABBR_NAME=?,USR_UDF_SAP_JobName=?,USR_UDF_SAP_UpperManagement_FLAG=?,USR_UDF_SAP_NAME_FORMAT=?,USR_UDF_SAP_PREV_EMPLOYEE_FLAG=?,USR_UDF_SAP_PositionCode=?,USR_UDF_SAP_EMPLOYEE_GROUP=?,USR_UDF_SAP_EMPLOYEE_SUB_GROUP=?,USR_UDF_SAP_PERSONNEL_SUB_AREA=?,USR_UDF_SAP_ActionStatus=?,USR_UDF_SAP_COST_CENTER=?,USR_UDF_SAP_PositionName=?,USR_UDF_SAPAccount=?,USR_START_DATE=?,USR_UDF_SAP_START_DATE=?,USR_END_DATE=?,USR_STATUS=?,USR_UDF_SAP_USER_STATUS=?,USR_MANAGER=?,USR_MANAGER_EMP_NO=?,USR_PARENT_OrgName=? where  USR_EMP_NO=? and USR_LOGIN=? ", [map.FIRSTNAME, map.LASTNAME, map.MIDDLENAME, map.PhoneNumber, map.MobileNumber, map.SSN, map.BIRTHDATE, map.EmailAddress, map.PRIMARY_LOGIN_ID, map.OrgName, map.HOME_POSTAL_ADDRESS, map.POSTAL_ADDRESS, map.POSTALCODE, map.STREET, map.STATE, map.COUNTRY, map.OrgCode, map.EmployeeNumber, map.LOCATION, map.PERSONNELAREA, map.Chief, map.ROLE, map.JobCode, map.MUID, map.CompanyName, map.COMPANYCODE, map.ActionType, map.NICKNAME, map.PhoneExtension, map.ALT_PhoneNumber, map.BULDINGNUMBER, map.ROOMNUMBER, map.LEGACY_COMPANYCODE, map.COMPANYABBREVIATEDNAME, map.JobName, map.UpperManagement, map.NAMEFORMAT, map.PREVIOUSEMPLOYEEFLAG, map.PositionCode, map.EMPLOYEEGROUP, map.EMPLOYEESUBGROUP, map.PERSONNELSUBAREA, map.ActionStatus, map.COSTCENTER, map.PositionName, map.SAPAccount, map.START_DATE, map.SAP_START_DATE, map.END_DATE, map.STATUS, map.SAP_STATUS, map.MANAGER, map.ManagerEMPNO, map.Parent_OrgName, map.EmployeeNumber, map.PRIMARY_LOGIN_ID])
                    UpdateToStage(saviyntsql,map)
                }
            } else if (!primaryLogin ) {
                log.debug("Main:Process the user record for condition if(!primaryLogin) as PIC not available from source")

                //*****calling getNewPic****
                log.debug("Main:****Executing 'getNewPic' function for getting PIC value for ${row.EmployeeNumber}**** ")
                def userAttributes =getNewPic(saviyntsql,holdsql,employeeNumber,lastName,birthdate,ssn,firstname)

                primaryLogin = userAttributes.picCandidate
                userExists = userAttributes.userExists
                //isUpdate=userAttributes.picExists
                isExceptional=userAttributes.isExceptional
                log.info("Main:Exiting 'getNewPic':PrimaryLogin for user ${row.EmployeeNumber} is :${primaryLogin}")
                log.debug("Main:****Entering updatePICInfo function for ${row.EmployeeNumber} ")

                //*******Updating source tables******
                def tb=row.TableName
                log.info("Main:Upadating ${tb} with PIC: ${primaryLogin} for entry ${row.EmployeeNumber}")
                if(isExceptional){
                    def ExceptionalprimaryLogin='#Exception#'
                    updatePICInfo (holdsql, tb, ExceptionalprimaryLogin, employeeNumber, lastName, birthdate, ssn)
                }else{
                    updatePICInfo (holdsql, tb, primaryLogin, employeeNumber, lastName, birthdate, ssn)
                }

                //******Uploading data to Stage table******
                // if ((primaryLogin && !row.BTLPersonnel) || (row.BTLPersonnel && userExists)){
                if ((primaryLogin) || (userExists)){
                    log.debug("Main:Getting user information from 'validateAttributes' function")
                    def map=validateAttributes(saviyntsql,holdsql,row,primaryLogin)

                    try {
                        if(isExceptional) {
                            //saviyntsql.execute('INSERT INTO mpc_saviynt_stage_table(USR_FIRST_NAME,USR_LAST_NAME,USR_MIDDLE_NAME,USR_TELEPhoneNumber,USR_MOBILE,USR_UDF_USR_UDF_SSN2,USR_UDF_USR_UDF_BIRTHDAY2,USR_EMAIL,USR_LOGIN,USR_OrgName,USR_HOME_POSTAL_ADDRESS,USR_POSTAL_ADDRESS,USR_POSTAL_CODE,USR_STREET,USR_STATE,USR_COUNTRY,USR_LOCATION,USR_DEPT_NO,USR_EMP_NO,USR_UDF_SAP_PERSONNELAREA,USR_UDF_Chief,USR_EMP_TYPE,USR_UDF_USR_UDF_EXCHLYNCACCESS,USR_UDF_USR_UDF_INETACCESS,USR_JobCode,USR_UDF_MARATHON_UNIVERSAL_ID,USR_UDF_SAP_COMPANY_FULL_NAME,USR_UDF_SAP_COMPANY_CODE,USR_UDF_SAP_ActionType,USR_UDF_NICKNAME,USR_UDF_PHONE_EXTENSION,USR_UDF_ALT_PhoneNumber,USR_UDF_SAP_BUILDING_NUMBER,USR_UDF_SAP_ROOM_NUMBER,USR_UDF_SAP_LEGACY_COMP_CODE,USR_UDF_SAP_COMPANY_ABBR_NAME,USR_UDF_SAP_JobName,USR_UDF_SAP_UpperManagement_FLAG,USR_UDF_SAP_NAME_FORMAT,USR_UDF_SAP_PREV_EMPLOYEE_FLAG,USR_UDF_SAP_PositionCode,USR_UDF_SAP_EMPLOYEE_GROUP,USR_UDF_SAP_EMPLOYEE_SUB_GROUP,USR_UDF_SAP_PERSONNEL_SUB_AREA,USR_UDF_SAP_ActionStatus,USR_UDF_SAP_COST_CENTER,USR_UDF_SAP_PositionName,USR_UDF_SAPAccount,USR_START_DATE,USR_UDF_SAP_START_DATE,USR_END_DATE,USR_STATUS,USR_UDF_SAP_USER_STATUS,USR_MANAGER,USR_MANAGER_EMP_NO,USR_PARENT_OrgName,USR_ISEXCEPTIONAL) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',[map.FIRSTNAME,map.LASTNAME,map.MIDDLENAME,map.PhoneNumber,map.MobileNumber,map.SSN,map.BIRTHDATE,map.EmailAddress,map.PRIMARY_LOGIN_ID,map.OrgName,map.HOME_POSTAL_ADDRESS,map.POSTAL_ADDRESS,map.POSTALCODE,map.STREET,map.STATE,map.COUNTRY,map.LOCATION,map.OrgCode,map.EmployeeNumber,map.PERSONNELAREA,map.Chief,map.ROLE,map.MailEnabled,map.InternetAccess,map.JobCode,map.MUID,map.CompanyName,map.COMPANYCODE,map.ActionType,map.NICKNAME,map.PhoneExtension,map.ALT_PhoneNumber,map.BULDINGNUMBER,map.ROOMNUMBER,map.LEGACY_COMPANYCODE,map.COMPANYABBREVIATEDNAME,map.JobName,map.UpperManagement,map.NAMEFORMAT,map.PREVIOUSEMPLOYEEFLAG,map.PositionCode,map.EMPLOYEEGROUP,map.EMPLOYEESUBGROUP,map.PERSONNELSUBAREA,map.ActionStatus,map.COSTCENTER,map.PositionName,map.SAPAccount,map.START_DATE,map.SAP_START_DATE,map.END_DATE,map.STATUS,map.SAP_STATUS,map.MANAGER,map.ManagerEMPNO,map.Parent_OrgName],1)
                            log.debug("Main:Inserting user ${row.EmployeeNumber} Name:${row.FIRSTNAME},${row.LASTNAME} into mpc_saviynt_stage_table with Exceptional flag true")
                            InsertExceptionalToStage(saviyntsql,map)
                        }else{
                            log.debug("Main:Inserting user ${row.EmployeeNumber} Name:${row.FIRSTNAME},${row.LASTNAME} into mpc_saviynt_stage_table")
                            InsertToStage(saviyntsql,map)
                        }
                    }catch(SQLException ex) {
                        saviyntsql.rollback()
                        log.info(ex.getMessage())
                    }
                    //log.info("Main:Successfully uploaded the user record ${row.EmployeeNumber}")
                }
            }
        }//ending eachrow
        saviyntsql.close()
        holdsql.close()
    }//ending Main method

//******************************************************
    //****************** PIC assignment ********************
    //******************************************************

/**
 *Method to generate PIC id for user
 *either reuse the existing PIC for user or fetch a PIC from Picsavailable table
 @param picCandidate Userlogin for the user
 @param isExceptional flag to decide exceptional user record


 */
    static def getNewPic(Sql saviyntsql,holdsql,Integer employeeNumber, String lastname, String birthdate, String ssn,String firstname) {

        def userExists = false
        def picExists = true
        def isExceptional=false

        String picCandidate
//  if the record comes from _HIRE table,
//  look for PIC in MPC_HOLD_EMP_CURRENT table based on EmployeeNumber
        //if (!isBTL && employeeNumber) {
        if (employeeNumber) {
            log.info("Look for PIC in MPC_HOLD_EMP_CURRENT table based on EmployeeNumber")
            picCandidate = holdsql.firstRow('SELECT primary_login_id FROM HOLD_EMPL_CURRENT WHERE actions_perno = ?',[employeeNumber])?.primary_login_id
            log.info("PICGENPICGEN: PIC ${picCandidate} found in HOLD_EMPL_CURRENT for employee ID:${employeeNumber} and lastname ${lastname}")
            //println "PICGENPICGEN: PIC  ${picCandidate} found in HOLD_EMPL_CURRENT for employee ID:${employeeNumber}"
        }
//---------------------------
//Look in saviynt through userManager to find a user with the same employee number and look for PIC
        if(!picCandidate && employeeNumber) {
            //if (employeeNumber) {
            log.info("Look in saviynt through userManager to find a user with the same employee number and look for PIC")
            saviyntsql.eachRow('select username from users where username not like "%@%" and  EMPLOYEEID = ?',[employeeNumber]){
                uname->
                    picCandidate=uname.username
                    log.info("PICGENPICGEN: Found PIC ${picCandidate} in saviynt by Employee Number ${employeeNumber}")
                    //println "PICGENPICGEN: Found PIC ${picCandidate} in saviynt by Employee Number ${employeeNumber}"
            }
        }

//-------------------------
//try to find a match on lastname, mmdd, last 4 of SSN
        if (!picCandidate && lastname && birthdate && ssn){
            log.info("try to find a match on lastname, mmdd, last 4 of SSN")
            def users=saviyntsql.firstRow('select username from users where lastname=? and customproperty1=? and customproperty2=?',[lastname,ssn,birthdate])
            if(users){
                picCandidate=users.username
                //println "saviynt username ${picCandidate}"
                log.info("PICGENPICGEN: Found PIC ${picCandidate} in saviynt by Lastname, Birthday, SSN")
                //println "PICGENPICGEN: Found PIC ${picCandidate} in saviynt by Lastname, Birthday, SSN"
            }
        }

//try to find a match on firstname,lastname and birthdate
        if(!picCandidate && lastname && birthdate && firstname) {
            log.info("Look in saviynt to find a user with the same lastanme & birthdate and look for PIC")
            def rec=saviyntsql.firstRow('select username from users where firstname=? and lastname=? and customproperty2=?',[firstname,lastname,birthdate])
            if(rec) {
                picCandidate=rec.username
                log.info("User found ${picCandidate} in saviynt")
                log.info("Adding the user record to exceptional block-------Inserting stage table with isExceptional Flag true")
//Adding the user to exceptional block-------Inserting Exceptional_user_record table with this user record
                isExceptional=true
            }
        }

//-------------
// Nothing exists in the database or in saviynt - get new PIC

        if (!picCandidate) {
            log.info("As Nothing exists in the database or in saviynt - get new PIC ")
            picExists = false
            log.info('PICGENPICGEN: Pull PIC from table PicsAvailable')
            picCandidate = saviyntsql.firstRow('SELECT available.pic as id FROM mpc_picsavailable available INNER JOIN (SELECT pic FROM mpc_picsavailable WHERE reserved = 0 AND datereserved IS NULL ORDER BY rand()) AS selection ON available.pic = selection.pic WHERE NOT EXISTS (select attribute_value from request_access_attrs raa join request_access ra on ra.request_accesskey=raa.request_access_key where raa.attributelable="User Login" and ra.status in (1,2) and attribute_value = available.pic COLLATE utf8_general_ci union select username from users where username = available.pic COLLATE utf8_general_ci) limit 1')?.id            //picCandidate = saviyntsql.firstRow('SELECT pic FROM mpc_picsavailable WHERE reserved = 0 AND datereserved IS NULL and pic not in (select username COLLATE utf8_general_ci from users) ORDER BY pic limit 1')?.pic
            //SELECT pic as ID  FROM PicsAvailable WHERE reserved = 0 AND datereserved IS NULL and pic not in (select username COLLATE utf8_general_ci from users) ORDER BY pic limit 1;
            }

        if (picCandidate != null) {
            log.info("PICGENPICGEN: PIC ${picCandidate} generated for employee ID:${employeeNumber}  with lastname ${lastname}")
            if (!picExists) {
                log.info("PICGENPICGEN: Saving ${picCandidate} as reserved PIC")
                savePic(picCandidate, saviyntsql)
            }
            userExists = picExists
            log.info("PICGENPICGEN: user already existed:${userExists}")
        }
        [picCandidate:picCandidate , userExists:userExists,picExists:picExists,isExceptional:isExceptional]
    }

/**
 * Method to update Picsavailable table for picCandidate entry
 * with reserved=1 and datereserved= current system date
 */


//*********************************************************
//************* save new PIC generation *******************
//*********************************************************
    static void savePic(String pic, Sql sql) {
        log.debug("SAVEPIC:Updating PicsAvailable table for entry ${pic}")
        def rowcount = sql.executeUpdate("UPDATE mpc_picsavailable SET reserved = 1, datereserved = sysdate() WHERE pic = ?", [pic])
        if (rowcount < 1) {
            log.error("PIC code not saved in PicsAvailable: ' + pic + '  ...trying PicsAvailable3")
        }
        sql.commit()
    }

/*
 * Method to update source tables
 * Update the user record in source table with generated PIC value
 */

//*******************************************************
//************* update source tables *******************
//*******************************************************
    static def updatePICInfo (def sql, def tableName, def primaryLogin, def employeeNumber, def lastName, def birthdate, def ssn) {
        if (employeeNumber) {
            log.debug("UPDATESOURCE:Updating ${tableName} with PIC value ${primaryLogin} for user Employee Number:${employeeNumber}")
            def rowcount=sql.executeUpdate("UPDATE ${tableName} SET primary_login_id = ? WHERE actions_perno = ?", [primaryLogin, employeeNumber])
            if (rowcount < 1) {
                log.error("PIC code not saved in ${tableName} : ${primaryLogin}")
            }
            sql.commit()
            log.debug("UPDATED PIC ${primaryLogin} in ${tableName} by EmployeeNumber = ${employeeNumber}")
        } else if ( lastName && birthdate && ssn ) {
            log.debug("UPDATESOURCE:Updating ${tableName} with PIC value ${primaryLogin} by lastname:${lastName},birthdate:${birthdate},ssn:${ssn}")
            sql.executeUpdate("UPDATE ${tableName} SET primary_login_id = ? WHERE lastname = ? AND birthdate = ? AND ssn = ?", [primaryLogin, lastName, birthdate, ssn])
            log.debug("UPDATES PIC ${primaryLogin} in ${tableName}  by lastname:${lastName}, birthdate:${birthdate}, ssn:${ssn}")
            sql.commit()
        } else {
            log.error("User Not updated in table ${tableName}  by lastname:${lastName}, birthdate:${birthdate}, ssn:${ssn}")
            //println "User Not updated in table"
        }
    }

/*
// * Method to validate each user attribute and
// * generate a Marathon universal id for user
// * Assign manager to new user
// * @param mymap Hash map holds all user attributes
// */
////*******************************************************
////**************validateAttributes function *****************
////*******************************************************
    static def validateAttributes(def saviyntsql,def holdsql,def row,def primaryLogin ) {
        GroovyClassLoader classLoader = new GroovyClassLoader()
        Class validationClazz = classLoader.parseClass(new File("/opt/saviynt/mpc_hr_sync/Validation.groovy"))
        //Class validationClazz = classLoader.parseClass(new File("Validation.groovy"));
        log.debug("----loading File Validation.groovy--------")
        def validator = validationClazz.newInstance()

        Class textScrubberClazz = classLoader.parseClass(new File("/opt/saviynt/mpc_hr_sync/TextScrubber.groovy"))
        //Class textScrubberClazz = classLoader.parseClass(new File("TextScrubber.groovy"));
        log.debug("----loading File TextScrubber.groovy--------")
        def scrubber = textScrubberClazz.newInstance()

        String uid = primaryLogin   // row.user_key_id ?: primaryLogin will use primaryLogin if key is null
        Map userAttributes = getAttributes(saviyntsql,uid)

        def mymap = [:]
        def validationErrors = [:]

        /*def firstName = scrubber.cleanName(row.FirstName)
        if (validator.isNameTextValid(firstName)) {
            log.debug("Validate First Name: ${firstName}")
        } else {
            log.error("Failed Validation for First Name: ${row.FirstName}")
            validationErrors << [FIRSTNAME: row.FirstName]
            firstName =  null
        }

        def lastName = scrubber.cleanName(row.lastname)
        if (validator.isNameTextValid(lastName)) {
            log.debug("Validate Last Name: ${lastName}")
        } else {
            log.error("Failed Validation for Last Name: ${row.lastname}")
            validationErrors << [LASTNAME: row.lastname]
            lastName = null
        }

        def middleName = scrubber.cleanName(row.middlename)
        if (validator.isNameTextValid(middleName)) {
            log.debug("Validate Middle Name: ${middleName}")
            middleName=row.middlename
        } else {
            log.error("Failed Validation for Middle Name: ${row.middlename}")
            validationErrors << [MIDDLENAME: row.middlename]
            middleName = null
        }*/
        mymap[new String("FirstName")]=row.FirstName
        mymap[new String("LastName")]= row.LastName
        mymap[new String("MiddleName")]= row.MiddleName

        def phone = scrubber.cleanPhoneText(row.PhoneNumber)
        if (validator.isTelephoneValid(phone)) {
            log.debug("Validate Phone Number: ${phone}")
            mymap["PhoneNumber"]= phone
        } else {
            log.error("Failed Validation for Phone Number: ${row.PhoneNumber}")
            validationErrors << [PHONENUMBER: row.PhoneNumber]
        }
        def mobile = scrubber.cleanPhoneText(row.MobileNumber)
        if (validator.isMobileValid(mobile)) {
            log.debug("Validate Mobile Number: ${mobile}")
            mymap["MobileNumber"]= mobile
        } else {
            log.error("Failed Validation for Mobile Number: ${row.MobileNumber}")
            validationErrors << [MOBILENUMBER: row.MobileNumber]
        }

        if (validator.isFourValidDigits(row.ssn)) {
            log.debug("Validate SSN: XXX-XX-XXXX")
            mymap[("SSN")]= row.ssn
        } else {
            log.error("Failed Validation for SSN: XXX-XX-XXXX")
            validationErrors << [SSN:row.ssn]
        }

        if (validator.isDobValid(row.birthdate)) {
            log.debug("Validate Birthday: ${row.birthdate}")
            mymap["BirthDate"]=row.BirthDate
        } else {
            log.error("Failed Validation for Birthday: ${row.birthdate}")
            validationErrors << [BIRTHDATE: row.birthdate]
        }

        mymap["DisplayName"]=row.DisplayName
        mymap[("EmailAddress")]= row.EmailAddress
        mymap["PrimaryLoginId"]=primaryLogin
        mymap["OrgName"]= row.OrgName
        mymap["Parent_OrgName"]=row.Parent_OrgName
        mymap["PostalCode"]=row.PostalCode
        mymap["Street"]= row.Street
        mymap["City"]=row.City
        mymap["State"]= row.State
        mymap["Country"]= row.Country
        mymap["OrgCode"]= row.OrgCode
        mymap["EmployeeNumber"]= row.EmployeeNumber
        mymap["PersonnelArea"]= row.PersonnelArea
        mymap["Chief"]=row.Chief
        mymap["EmployeeType"] = "Employee"
        mymap["MailEnabled"]= row.MailEnabled
        mymap["InternetAccess"]= row.InternetAccess
        mymap["JobCode"]= row.JobCode
        mymap["LastChanged"]=row.LastChanged
        mymap["Actions_CHANGEDON"]=row.Actions_CHANGEDON
        mymap["Actions_BEGDA"]=row.Actions_BEGDA
        mymap["InitialHireDate"]=row.InitialHireDate
        mymap["Actions_ENDDA"]=row.Actions_ENDDA
        mymap["Suffix"]=row.Suffix
        mymap["PersonalCity"]=row.PersonalCity
        mymap["PersonalState"]=row.PersonalState
        mymap["PersonnelAreaDescription"]=row.PersonnelAreaDescription


        //Fetching MUID from picsavailable table
        if (userAttributes?.marathon_universal_id) {
            mymap["Muid"]= userAttributes?.marathon_universal_id
        }else {
            log.debug("[MUIDGEN]:Get Marathon_Universal_ID for user ${row.EmployeeNumber}")
            def Marathon_Universal_id = saviyntsql.firstRow('SELECT muid FROM mpc_picsavailable WHERE pic=?', [primaryLogin])
            if (Marathon_Universal_id) {
                log.info("[MUIDGEN]:Assigned MUID ${Marathon_Universal_id}")
                mymap["Muid"] = Marathon_Universal_id.muid
                log.info("***********MUID:${Marathon_Universal_id} is assigned for the user***************")
            }
        }
        mymap["CompanyName"]= row.CompanyName
        mymap["CompanyCode"]=row.CompanyCode
        mymap["ActionType"]= row.ActionType
        mymap["NickName"]=row.NickName
        mymap["PhoneExtension"]= row.PhoneExtension
        mymap["RoomNumber"]= row.RoomNumber
        mymap["JobName"]=row.JobName
        mymap["UpperManagement"]=row.UpperManagement
        mymap["NameFormat"]=row.NameFormat
        mymap["PositionCode"]= row.PositionCode
        mymap["EmployeeGroup"]=row.EmployeeGroup
        mymap["EmployeeSubGroup"]=row.EmployeeSubGroup
        mymap["PersonnelSubArea"]= row.PersonnelSubArea
        mymap["ActionStatus"]=row.ActionStatus
        mymap["CostCenter"]=row.CostCenter
        mymap["PositionName"]=row.PositionName
        mymap["PrimaryOU"]=row.PrimaryOU
        mymap["DetailedStatus"]=row.DetailedStatus
        mymap["EmployeeStatus"]=row.EmployeeStatus
        mymap["AlternateOU"]=row.AlternateOU
        mymap["LegacyEmployeeNumber"]=row.LegacyEmployeeNumber
        mymap["AlternateLoginId"]=row.AlternateLoginId
        mymap["TableName"]=row.TableName

        def sapAccount = row.SAPAccount
        if (!row.SAPAccount) {
            sapAccount = primaryLogin
            saveSAPAccount(holdsql, row.TableName, primaryLogin,row.EmployeeNumber)
            log.info( "SAP account was replaced with PIC value ${primaryLogin}")
        }
        mymap["SAPAccount"]= sapAccount

        String dateFormatString = "yyyy-MM-dd HH:mm:ss"
        String userStatus = row.EmployeeStatus
        String actionsDate = new java.text.SimpleDateFormat(dateFormatString).format(row.actions_begda.getTime())

        def milliSecondsInADay = 1000 * 60 * 60 * 24
        java.sql.Date startDate = new java.sql.Date( row.actions_begda.getTime() - ( 14 * milliSecondsInADay ))
        log.info("MPC:(trusted_reconciliation.groovy): Start Date processing... ")
        log.info("  ...SAP Actions Date:${actionsDate}")
        log.info("  ...Saviynt Start Date: ${userAttributes?.start_date}")
        def status_value=["Active","LOA_Active","LOA_Inactive"]
        if ( status_value.contains(userStatus)) {
            if (!userAttributes?.start_date || !userAttributes?.current_status.equals('Active') ) {
                log.info("No start_date found in saviynt... ")
                log.info("No current_status found in saviynt... ")
                log.info( "Adding attribute START_DATE which is actions_begda minus 14 days... ")
                mymap[new String("StartDate")]=new java.text.SimpleDateFormat(dateFormatString).format(startDate)
                //log.info("Startdate:${StartDate}")
            } else {
                log.info("Using current start_date found in saviynt... ${userAttributes?.start_date}")
            }
            //mymap["SAP_START_DATE"]= actionsDate
            log.info( "Adding attribute SAP_START_DATE which is actions_begda... ")
            /* if (!userAttributes?."End Date") {
               def futureEndDate = new GregorianCalendar()
               futureEndDate.set(Calendar.DAY_OF_MONTH, 31)
               futureEndDate.set(Calendar.MONTH, 11)
               futureEndDate.set(Calendar.YEAR, 9999) */
            def Enddate='9999-12-31 00:00:00'
            mymap["EndDate"]= Enddate
            log.info("Enddate:${Enddate}")
            /*} else {
                log.error( "End Date not set... ")
            }*/
        }
        else if (userAttributes?.end_date && !userAttributes?.current_status.equals('Active')) {
            log.info("Enddate is set to Saviynt value - ${userAttributes.end_date}")
            mymap["EndDate"] = userAttributes.end_date
            mymap["StartDate"] = userAttributes.start_date
        } else {
            log.info("End Date being set to currentdate")
            def date = new Date()
            mymap["EndDate"] = new java.text.SimpleDateFormat(dateFormatString).format(date)
            mymap["StartDate"] = userAttributes.start_date
        }

        String defaultAdminManager = 'admin'
        def manager=row.Supervisor_PrimaryLoginId
        mymap[new String("Supervisor_PrimaryLoginId")]= manager ?: defaultAdminManager   //if manager isn't found, use default "System Administrator"
        mymap["Supervisor_EmployeeNumber"]=row.Supervisor_EmployeeNumber
        mymap
    }

    /*
 * Method to save SAP account to name and
 * Update SAP detail in source table if its null
  */
//*******************************************************
//************** functionS for saving SAP account details in source *****************
//*******************************************************

    static def saveSAPAccount(def sql,def TableName,def primaryLogin,def employeeNumber) {
        if (primaryLogin) {
            log.debug("UPDATESOURCE:Updating ${TableName} with Sapaccount value ${primaryLogin} for user Employee Number:${employeeNumber}")
            def rowcount=sql.executeUpdate("UPDATE ${TableName} SET SAP_ACCOUNT = ? WHERE actions_perno = ?", [primaryLogin, employeeNumber])
            if (rowcount < 1) {
                log.error("SAPAccount not saved in ${TableName} : ${primaryLogin}")
            }
            sql.commit()
            log.debug("UPDATED SAPAccount ${primaryLogin} in ${TableName} by EmployeeNumber = ${employeeNumber}")

        }
    }

    /*
 * Method to insert user to stage table
  */
//*******************************************************
//************** Insert to stage table function *****************
//*******************************************************

    static def InsertToStage(def sql,def map)  throws SQLException{
        try{
            sql.execute('INSERT INTO Mpc_SaviyntStageTable(LastChanged,Actions_CHANGEDON,Actions_BEGDA,Actions_ENDDA,StartDate,EndDate,EmployeeNumber,LegacyEmployeeNumber,Muid,ActionType,ActionStatus,InitialHireDate,DetailedStatus,EmployeeStatus,EmployeeType,FirstName,LastName,MiddleName,Suffix,NickName,NameFormat,BirthDate,SSN,PersonalCity,PersonalState,CompanyCode,CompanyName,OrgCode,OrgName,Parent_OrgName,PersonnelSubArea,CostCenter,EmployeeGroup,EmployeeSubGroup,PositionCode,PositionName,JobCode,JobName,Chief,UpperManagement,PrimaryLoginId,AlternateLoginId,SAPAccount,EmailAddress,PhoneNumber,PhoneExtension,MobileNumber,PersonnelArea,PersonnelAreaDescription,RoomNumber,Country,Street,City,State,PostalCode,Supervisor_EmployeeNumber,Supervisor_PrimaryLoginId,PrimaryOU,AlternateOU,InternetAccess,MailEnabled,TableName,DisplayName,createdate,updatedate) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,sysdate(),sysdate())',[map.LastChanged,map.Actions_CHANGEDON,map.Actions_BEGDA,map.Actions_ENDDA,map.StartDate,map.EndDate,map.EmployeeNumber,map.LegacyEmployeeNumber,map.Muid,map.ActionType,map.ActionStatus,map.InitialHireDate,map.DetailedStatus,map.EmployeeStatus,map.EmployeeType,map.FirstName,map.LastName,map.MiddleName,map.Suffix,map.NickName,map.NameFormat,map.BirthDate,map.SSN,map.PersonalCity,map.PersonalState,map.CompanyCode,map.CompanyName,map.OrgCode,map.OrgName,map.Parent_OrgName,map.PersonnelSubArea,map.CostCenter,map.EmployeeGroup,map.EmployeeSubGroup,map.PositionCode,map.PositionName,map.JobCode,map.JobName,map.Chief,map.UpperManagement,map.PrimaryLoginId,map.AlternateLoginId,map.SAPAccount,map.EmailAddress,map.PhoneNumber,map.PhoneExtension,map.MobileNumber,map.PersonnelArea,map.PersonnelAreaDescription,map.RoomNumber,map.Country,map.Street,map.City,map.State,map.PostalCode,map.Supervisor_EmployeeNumber,map.Supervisor_PrimaryLoginId,map.PrimaryOU,map.AlternateOU,map.InternetAccess,map.MailEnabled,map.TableName,map.DisplayName])
            sql.commit()
            log.debug("User record is Inserted successfully")
        }catch(SQLException ex) {
            sql.rollback()
            log.error(ex.getMessage())
        }
    }

    /*
 * Method to insert Exceptional user to stage table
 * With Exception flag as true and Exception comments
  */
//*******************************************************
//************** Insert to stage table with Exception user function *****************
//*******************************************************
    static def InsertExceptionalToStage(def sql,def map) throws SQLException{
        try{
            sql.execute('INSERT INTO Mpc_SaviyntStageTable(LastChanged,Actions_CHANGEDON,Actions_BEGDA,Actions_ENDDA,StartDate,EndDate,EmployeeNumber,LegacyEmployeeNumber,Muid,ActionType,ActionStatus,InitialHireDate,DetailedStatus,EmployeeStatus,EmployeeType,FirstName,LastName,MiddleName,Suffix,NickName,NameFormat,BirthDate,SSN,PersonalCity,PersonalState,CompanyCode,CompanyName,OrgCode,OrgName,Parent_OrgName,PersonnelSubArea,CostCenter,EmployeeGroup,EmployeeSubGroup,PositionCode,PositionName,JobCode,JobName,Chief,UpperManagement,PrimaryLoginId,AlternateLoginId,SAPAccount,EmailAddress,PhoneNumber,PhoneExtension,MobileNumber,PersonnelArea,PersonnelAreaDescription,RoomNumber,Country,Street,City,State,PostalCode,Supervisor_EmployeeNumber,Supervisor_PrimaryLoginId,PrimaryOU,AlternateOU,InternetAccess,MailEnabled,TableName,DisplayName,ExceptionFlag,ExceptionDetails,createdate,updatedate) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,sysdate(),sysdate())',[map.LastChanged,map.Actions_CHANGEDON,map.Actions_BEGDA,map.Actions_ENDDA,map.StartDate,map.EndDate,map.EmployeeNumber,map.LegacyEmployeeNumber,map.Muid,map.ActionType,map.ActionStatus,map.InitialHireDate,map.DetailedStatus,map.EmployeeStatus,map.EmployeeType,map.FirstName,map.LastName,map.MiddleName,map.Suffix,map.NickName,map.NameFormat,map.BirthDate,map.SSN,map.PersonalCity,map.PersonalState,map.CompanyCode,map.CompanyName,map.OrgCode,map.OrgName,map.Parent_OrgName,map.PersonnelSubArea,map.CostCenter,map.EmployeeGroup,map.EmployeeSubGroup,map.PositionCode,map.PositionName,map.JobCode,map.JobName,map.Chief,map.UpperManagement,map.PrimaryLoginId,map.AlternateLoginId,map.SAPAccount,map.EmailAddress,map.PhoneNumber,map.PhoneExtension,map.MobileNumber,map.PersonnelArea,map.PersonnelAreaDescription,map.RoomNumber,map.Country,map.Street,map.City,map.State,map.PostalCode,map.Supervisor_EmployeeNumber,map.Supervisor_PrimaryLoginId,map.PrimaryOU,map.AlternateOU,map.InternetAccess,map.MailEnabled,map.TableName,map.DisplayName,1,'Exception conversion'])
            sql.commit()
            log.debug("User record is Inserted successfully with Exceptional flag as true")
        }catch(SQLException ex) {
            sql.rollback()
            log.error(ex.getMessage())
        }
    }

    /*
 * Method to update user to stage table
  */
//*******************************************************
//************** Update to stage table function *****************
//*******************************************************
    static def UpdateToStage(def sql,def map) throws SQLException{
        try{
            def rowcount=sql.executeUpdate("update Mpc_SaviyntStageTable set LastChanged=?,Actions_CHANGEDON=?,Actions_BEGDA=?,Actions_ENDDA=?,StartDate=?,EndDate=?,EmployeeNumber=?,LegacyEmployeeNumber=?,Muid=?,ActionType=?,ActionStatus=?,InitialHireDate=?,DetailedStatus=?,EmployeeStatus=?,EmployeeType=?,FirstName=?,LastName=?,MiddleName=?,Suffix=?,NickName=?,NameFormat=?,BirthDate=?,SSN=?,PersonalCity=?,PersonalState=?,CompanyCode=?,CompanyName=?,OrgCode=?,OrgName=?,Parent_OrgName=?,PersonnelSubArea=?,CostCenter=?,EmployeeGroup=?,EmployeeSubGroup=?,PositionCode=?,PositionName=?,JobCode=?,JobName=?,Chief=?,UpperManagement=?,PrimaryLoginId=?,AlternateLoginId=?,SAPAccount=?,EmailAddress=?,PhoneNumber=?,PhoneExtension=?,MobileNumber=?,PersonnelArea=?,PersonnelAreaDescription=?,RoomNumber=?,Country=?,Street=?,City=?,State=?,PostalCode=?,Supervisor_EmployeeNumber=?,Supervisor_PrimaryLoginId=?,PrimaryOU=?,AlternateOU=?,InternetAccess=?,MailEnabled=?,TableName=?,DisplayName=?,updatedate=sysdate() where  EmployeeNumber=? and PrimaryLoginId=? ",[map.LastChanged,map.Actions_CHANGEDON,map.Actions_BEGDA,map.Actions_ENDDA,map.StartDate,map.EndDate,map.EmployeeNumber,map.LegacyEmployeeNumber,map.Muid,map.ActionType,map.ActionStatus,map.InitialHireDate,map.DetailedStatus,map.EmployeeStatus,map.EmployeeType,map.FirstName,map.LastName,map.MiddleName,map.Suffix,map.NickName,map.NameFormat,map.BirthDate,map.SSN,map.PersonalCity,map.PersonalState,map.CompanyCode,map.CompanyName,map.OrgCode,map.OrgName,map.Parent_OrgName,map.PersonnelSubArea,map.CostCenter,map.EmployeeGroup,map.EmployeeSubGroup,map.PositionCode,map.PositionName,map.JobCode,map.JobName,map.Chief,map.UpperManagement,map.PrimaryLoginId,map.AlternateLoginId,map.SAPAccount,map.EmailAddress,map.PhoneNumber,map.PhoneExtension,map.MobileNumber,map.PersonnelArea,map.PersonnelAreaDescription,map.RoomNumber,map.Country,map.Street,map.City,map.State,map.PostalCode,map.Supervisor_EmployeeNumber,map.Supervisor_PrimaryLoginId,map.PrimaryOU,map.AlternateOU,map.InternetAccess,map.MailEnabled,map.TableName,map.DisplayName,map.EmployeeNumber,map.PrimaryLoginId])
            if(rowcount>0){
                sql.commit()
                log.debug("User record is Updated successfully")
            }
        }catch(SQLException ex) {
            sql.rollback()
            log.error(ex.getMessage())
        }
    }

    /**
     *Method to get user attributes from Saviynt
     *like UserLogin, MUID, End Date, Start Date, Current status

     */
    static def getAttributes (def sql,String userLogin) {
        println "****${userLogin}*****"
        def results = [:]
        sql.eachRow("select customproperty3,startdate,enddate,case when statuskey=1 then 'Active' else 'Inactive' end as statuskey from users where username=?",[userLogin]){
            frow ->
                //println([frow.customproperty43,frow.startdate,frow.enddate,frow.statuskey])
                log.info("MPC:(trusted_reconciliation.groovy): getAttributes() from saviynt where User Login = ${userLogin}")
                log.info("('MPC:(trusted_reconciliation.groovy): getAttributes(): for user in saviynt... ')")
                log.info( "('MPC: (trusted_reconciliation.groovy): getAttributes() for user ${userLogin} found in saviynt)")
                results["marathon_universal_id"]=frow.customproperty3
                results["end_date"]=frow.enddate
                results["start_date"]=frow.startdate
                results["current_status"]=frow.statuskey
                log.info( "MPC:(trusted_reconciliation.groovy): getAttributes()... returning ${results} ")
        }
        results
    }

    /*
 * Method to decrypt database password
  */
//*******************************************************
//************** Decryption function *****************
//*******************************************************
    public static String decrypt(String encryptedData) throws Exception {
        Key key = generateKey()
        Cipher c = Cipher.getInstance(ALGO)
        c.init(Cipher.DECRYPT_MODE, key)
        byte[] decordedValue = DatatypeConverter.parseBase64Binary(encryptedData)
        byte[] decValue = c.doFinal(decordedValue)
        return new String(decValue, "UTF-8")
    }

    static Key generateKey() throws Exception {
        return new SecretKeySpec(keyValue, ALGO)
    }

    /*
 * Method to load property file
  */
//*******************************************************
//************** Load propery file function *****************
//*******************************************************
    static void loadPropertyFile() {
        log.debug("  *** Inside loadProperty ***")
        // Read Properties file
        String inputPropertiesFile ="/opt/saviynt/mpc_hr_sync/Utility.properties"
        //String inputPropertiesFile ="Utility.properties"
        try {
            if (!new File(inputPropertiesFile).exists())
                throw new FileNotFoundException(
                        "Error: Failed to find the input Properties File: " + inputPropertiesFile + "\n")

            Properties props = new Properties()
            FileInputStream fis = new FileInputStream(inputPropertiesFile)
            props.load(fis)
            fis.close()
            saviynt_DB_password = props.getProperty("saviynt_DB_password")
            hold_DB_password = props.getProperty("hold_DB_password")
        }catch(FileNotFoundException e) {
            log.error(" Excpetion in load property - " + e)
        }
    }

}