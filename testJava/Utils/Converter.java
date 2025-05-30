package Utils;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Scanner;

public class Converter {

  public static  <T>  T convert(Map source,Class<T> clazz)
    {
        ObjectMapper mapper = new ObjectMapper();
       return mapper.convertValue(source,clazz);
    }


  public static void convertDecimalToBinary() {
    Scanner sc=new Scanner(System.in);
    System.out.println("Enter a decimal number");
    int n=sc.nextInt();
    int  bin[]=new int[100];
    int i = 0;
    while(n > 0)
    {
    bin[i++] = n%2;
       n = n/2;
    }
   System.out.print("Binary number is : ");
    for(int j = i-1;j >= 0;j--)
   {
       System.out.print(bin[j]);
   }
  }

  public static void toCelsius() {
    double f, c;
             	    Scanner sc=new Scanner(System.in);	   	 
	    System.out.println("Enter  Fahrenheit temperature");
                   f=sc.nextDouble();
      Double celsiusNew = (f-32)*5/9;
	    System.out.println("Celsius temperature is = "+celsiusNew);
  }
}
System.out.println("Celsius temperature is = "+celsiusNew);