import com.amazonaws.auth.*
import com.amazonaws.services.elasticbeanstalk.*
import com.amazonaws.services.elasticbeanstalk.model.*
import com.amazonaws.services.s3.*

/**
* @author Kenneth Liu
*/

includeTargets << grailsScript("_GrailsInit")
includeTargets << grailsScript("_GrailsWar")
includeTargets << grailsScript("_GrailsPackage") //needed to access application settings
includeTargets << new File(awsElasticBeanstalkPluginDir, "scripts/_AwsAuthentication.groovy")

//TODO check what happens on a new installation before plugin is downloaded (breaks with new Grails version?)


target(awsEbDeploy: "Deploy Grails WAR file to AWS Elastic Beanstalk") {
    depends(loadAwsCredentials, compile, createConfig, configureWarName) 

    String warFileName

    //configureWarName target doesn't set the warName property since 2.2 (http://bit.ly/17uHfFm)
    if (binding.variables.containsKey("warName")) {
        warFileName = warName
    } else {
        warFileName = warCreator.warName
    }

    //output global variables coming from Grails scripts
    println "script metadata: ${metadata}"
    println "Grails settings warname ${grailsSettings.projectWarFile}"
    println "WAR file name: ${warFileName}"

    println 'Starting AWS Elastic Beanstalk deployment'

    def credentials = awsCredentials
    AWSElasticBeanstalk elasticBeanstalk = new AWSElasticBeanstalkClient(credentials)

    //TODO optionally set region here
    //TODO check number of deployed applications to watch for limit
    //TODO optionally purge old application versions

    println "Finding S3 bucket to upload WAR"
    //TODO log bucket creation
    String bucketName = elasticBeanstalk.createStorageLocation().getS3Bucket()

    File appWarFile = getAppWarFile(warFileName)
    def s3key = uniqueTempWarFileName(appWarFile)
    uploadToS3(credentials, appWarFile, bucketName, s3key)

    //TODO handle case where application does not yet exist - check application? (don't want to autocreate - disable autocreate flag?)
    //TODO handle case where target environment does not yet exist - check environment?

    //create a new application version
    println "Create application version with uploaded application"
    println "applicationName: ${applicationName}"
    println "environmentName: ${environmentName}"
    String versionLabel = getVersionLabel(appWarFile)
    println "versionLabel: ${versionLabel}"
    def createApplicationRequest = new CreateApplicationVersionRequest(
        applicationName: applicationName,
        versionLabel: versionLabel,
        description: description,
        autoCreateApplication: true, 
        sourceBundle: new S3Location(bucketName, s3key)
    )
    
    def createApplicationVersionResult = elasticBeanstalk.createApplicationVersion(createApplicationRequest)
    println "Created application version $createApplicationVersionResult"

    //deploy the deployed version to an existing environment
    println "Updating environment with uploaded application version"
    def updateEnviromentRequest = new UpdateEnvironmentRequest(environmentName:environmentName, versionLabel:versionLabel)
    def updateEnviromentResult = elasticBeanstalk.updateEnvironment(updateEnviromentRequest)
    println "Updated environment $updateEnviromentResult"

}

setDefaultTarget(awsEbDeploy)


private String getApplicationName() {
    def name = config.grails?.plugin?.awsElasticBeanstalk?.applicationName 
    if (!name) name = System.getProperty('awsElasticBeanstalk.applicationName')
    name ?: metadata.'app.name'
}

private String getEnvironmentName() {
    def name = config.grails?.plugin?.awsElasticBeanstalk?.environmentName
    if (!name) name = System.getProperty('awsElasticBeanstalk.environmentName')
    name ?: metadata.'app.name' + '-default' //the name of the default environment used in the AWS Console
    //FIXME this should be unique to account - needs to be truncated? - appname must be between 4 and 23 chars long
}

private String getDescription() {
    //TODO add customization of description using template
    //TODO use ISO date format here
    "Deployed on ${new Date()} from Grails AWS Elastic Beanstalk Plugin"
}

private String getVersionLabel(warFile) {
    //TODO provide for alternate algorithms for generating version label
    //def applicationVersion = metadata.getApplicationVersion()
    def label = getWarTimestamp(warFile)
    println "version label: ${label}"
    label
}

private File getAppWarFile(warFilename) {
    //FIXME this makes an assumption that the war file is in the basedir but in later versions of Grails it was moved
    //TODO check to make sure that the WAR actually exist
    //println "loading WAR file from basedir: ${basedir}"
    //println "war file name: ${warFilename}"
    new File(warFilename)
}

private String getWarTimestamp(File warFile) {
    def warDate = new Date(warFile.lastModified())
    warDate.format('yyyy-MM-dd_HH-mm-ss') //same as Jenkins BUILD_ID format
}

private uploadToS3(credentials, file, bucketName, key) {
    println "Uploading local WAR file ${file.name} to remote WAR file: ${key}"
    String s3key = URLEncoder.encode(key, 'UTF-8')

    println "Uploading WAR to S3 bucket ${bucketName}"
    AmazonS3 s3 = new AmazonS3Client(credentials)
    def s3Result = s3.putObject(bucketName, s3key, file)
    println "Uploaded WAR ${s3Result.versionId}"
}

/**
* Generates a unique file name for the uploaded WAR file in S3, based on the file's timestamp.
* //TODO what happens if the same file is uploaded twice?
*/
private String uniqueTempWarFileName(warFile) {
    def uuid = Long.toHexString(warFile.lastModified())
    "${uuid}-${warFile.name}"
}

