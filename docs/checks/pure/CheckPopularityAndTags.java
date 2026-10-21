import com.kashmirmenu.app.service.*; import com.kashmirmenu.app.enums.*; import java.util.*;
public class CheckPopularityAndTags { static int n=0; static void ok(boolean c,String m){ if(!c) throw new AssertionError(m); n++; }
 public static void main(String[] a){
  Map<Long,Long> m=new HashMap<>(); m.put(1L,50L); m.put(2L,40L); m.put(3L,40L); m.put(4L,30L); m.put(5L,4L);
  ok(PopularityRules.pick(m).equals(Set.of(1L,2L,3L)),"top3 with tie to lower id");
  ok(PopularityRules.pick(Map.of(1L,4L,2L,1L)).isEmpty(),"below minimum");
  ok(PopularityRules.pick(Map.of()).isEmpty(),"empty");
  ok(PopularityRules.pick(Map.of(9L,5L)).equals(Set.of(9L)),"exactly minimum");
  ok(MenuTags.parse(null,Allergen.class).isEmpty() && MenuTags.parse("",Allergen.class).isEmpty(),"undeclared");
  ok(MenuTags.parse("SOY,MILK, EGG",Allergen.class).equals(List.of(Allergen.MILK,Allergen.EGG,Allergen.SOY)),"enum order");
  ok(MenuTags.parse("MILK,ALIEN",Allergen.class).equals(List.of(Allergen.MILK)),"unknown skipped");
  ok(MenuTags.join(null,Allergen.class)==null,"null stays undeclared");
  ok(MenuTags.join(List.of(),Allergen.class).equals(""),"empty means declared none");
  ok(MenuTags.join(List.of(Allergen.SOY,Allergen.MILK,Allergen.SOY),Allergen.class).equals("MILK,SOY"),"sorted deduped");
  ok(MenuTags.tags(List.of(Allergen.TREE_NUT),Allergen::label).get(0).label().equals("Tree nuts"),"label");
  System.out.println("POPULARITY + TAGS: PASSED "+n+" assertions"); } }
