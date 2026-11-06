/** Exercise the visible Settings route to the existing connection editor. */
export async function openConnectionSettings(page) {
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await page.getByRole('dialog', { name: 'Settings', exact: true })
    .getByRole('button', { name: 'Manage server connections', exact: true }).click();
}
