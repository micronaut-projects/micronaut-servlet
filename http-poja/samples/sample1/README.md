export JAVA_HOME=`java_home v21`
mvn clean package native:compile
java -jar target/*.jar
GET / HTTP/1.1
Host: h


