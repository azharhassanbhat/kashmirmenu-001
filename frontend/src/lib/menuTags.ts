/*
 * The allergen and dietary vocabularies. They mirror the server's fixed lists (Allergen and
 * DietaryLabel enums): the server rejects any code it does not know, so adding one means adding it
 * in both places. Which of them apply to a dish is only ever the restaurant's own declaration.
 */
export const ALLERGENS: { code: string; label: string }[] = [
  { code: 'MILK', label: 'Milk' },
  { code: 'EGG', label: 'Egg' },
  { code: 'PEANUT', label: 'Peanut' },
  { code: 'TREE_NUT', label: 'Tree nuts' },
  { code: 'GLUTEN', label: 'Gluten (wheat)' },
  { code: 'SOY', label: 'Soy' },
  { code: 'FISH', label: 'Fish' },
  { code: 'SHELLFISH', label: 'Shellfish' },
  { code: 'SESAME', label: 'Sesame' },
  { code: 'MUSTARD', label: 'Mustard' },
];

export const DIETARY_LABELS: { code: string; label: string }[] = [
  { code: 'VEGAN', label: 'Vegan' },
  { code: 'JAIN', label: 'Jain' },
  { code: 'GLUTEN_FREE', label: 'Gluten free' },
  { code: 'DAIRY_FREE', label: 'Dairy free' },
  { code: 'NUT_FREE', label: 'Nut free' },
  { code: 'HALAL', label: 'Halal' },
];
