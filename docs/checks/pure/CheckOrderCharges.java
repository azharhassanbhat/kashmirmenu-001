import com.kashmirmenu.app.service.OrderCharges; import com.kashmirmenu.app.service.OrderCharges.Breakdown;
import java.math.BigDecimal;
public class CheckOrderCharges { static int n=0; static BigDecimal d(String s){return new BigDecimal(s);}
 static void eq(String exp, BigDecimal got, String m){ if(!d(exp).equals(got)) throw new AssertionError(m+": expected "+exp+" got "+got); n++; }
 static void bad(Runnable r,String m){ try{r.run();}catch(IllegalArgumentException e){n++;return;} throw new AssertionError("expected refusal "+m);}
 public static void main(String[] a){
  // defaults: behaves exactly like before the feature
  Breakdown b=OrderCharges.compute(d("1000.00"),d("100.00"),null,null,false);
  eq("900.00",b.total(),"no charges total"); eq("0.00",b.tax(),"no tax"); eq("0.00",b.serviceCharge(),"no service");
  // exclusive: 10% service, 5% tax on (net+service)
  b=OrderCharges.compute(d("1000.00"),d("0.00"),d("10"),d("5"),false);
  eq("100.00",b.serviceCharge(),"service 10%"); eq("55.00",b.tax(),"tax on 1100"); eq("1155.00",b.total(),"exclusive total"); eq("0.00",b.taxIncluded(),"nothing included");
  // discount first: coupon of 200 -> net 800, service 80, tax 44, total 924
  b=OrderCharges.compute(d("1000.00"),d("200.00"),d("10"),d("5"),false);
  eq("800.00",b.net(),"net"); eq("80.00",b.serviceCharge(),"service on net"); eq("44.00",b.tax(),"tax on 880"); eq("924.00",b.total(),"total after coupon");
  // tax only
  b=OrderCharges.compute(d("640.00"),d("0"),d("0"),d("5"),false); eq("32.00",b.tax(),"5% of 640"); eq("672.00",b.total(),"640+32");
  // inclusive: nothing added; embedded tax = 1050*5/105 = 50.00
  b=OrderCharges.compute(d("1050.00"),d("0"),d("0"),d("5"),true);
  eq("1050.00",b.total(),"inclusive total unchanged"); eq("0.00",b.tax(),"no tax added"); eq("50.00",b.taxIncluded(),"embedded tax");
  // inclusive + service: service added untaxed
  b=OrderCharges.compute(d("1050.00"),d("0"),d("10"),d("5"),true); eq("1155.00",b.total(),"inclusive plus service"); eq("105.00",b.serviceCharge(),"service on 1050");
  // rounding half up: 333.33 * 5% = 16.6665 -> 16.67
  b=OrderCharges.compute(d("333.33"),d("0"),d("0"),d("5"),false); eq("16.67",b.tax(),"half up"); eq("350.00",b.total(),"rounded total");
  // discount larger than subtotal never goes negative
  b=OrderCharges.compute(d("100.00"),d("150.00"),d("10"),d("5"),false); eq("0.00",b.total(),"floor at zero"); eq("0.00",b.net(),"net floor");
  // empty cart
  b=OrderCharges.compute(d("0"),d("0"),d("10"),d("5"),false); eq("0.00",b.total(),"empty");
  // always 2dp
  if(OrderCharges.compute(d("10"),d("0"),d("1"),d("1"),false).total().scale()!=2) throw new AssertionError("scale"); n++;
  // total = net + service + tax always
  b=OrderCharges.compute(d("777.77"),d("33.33"),d("7.5"),d("12"),false);
  eq(b.net().add(b.serviceCharge()).add(b.tax()).toPlainString(),b.total(),"sum identity");
  // bad rates
  bad(()->OrderCharges.compute(d("1"),d("0"),d("-1"),d("0"),false),"negative service");
  bad(()->OrderCharges.compute(d("1"),d("0"),d("0"),d("100.01"),false),"tax over 100");
  eq("2.00",OrderCharges.compute(d("1"),d("0"),d("0"),d("100"),false).total(),"100% tax is allowed");
  System.out.println("ORDER CHARGES: PASSED "+n+" assertions"); } }
