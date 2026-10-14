import type {
  ProviderConnectionDefinition,
  ProviderOAuthSettings,
} from '../sdk/typescript/src/operations/provider-credentials.ts';

/** HTTP endpoint drafts contain no credential material. Validation still runs through
 * provider.connection.save before submission; this view does not discover OAuth URLs.
 */
export function providerDefinitionEditor() {
  const element = document.createElement('fieldset');
  const legend = document.createElement('legend');
  legend.textContent = 'Server HTTP endpoint';
  element.append(legend);
  function input(label: string) {
    const row = document.createElement('label'),
      field = document.createElement('input');
    row.textContent = label;
    row.style.display = 'grid';
    row.style.marginBlock = '0.5rem';
    field.dataset['endpointField'] = label;
    row.append(field);
    element.append(row);
    return field;
  }
  function select(label: string, values: readonly string[]) {
    const row = document.createElement('label'),
      field = document.createElement('select');
    field.setAttribute('aria-label', label);
    row.textContent = label;
    field.dataset['endpointField'] = label;
    row.style.display = 'grid';
    for (const value of values) {
      const option = document.createElement('option');
      option.value = value;
      option.textContent =
        value === 'API_KEY'
          ? 'API key'
          : value === 'OAUTH_PKCE'
            ? 'Browser authorization (OAuth)'
            : value === 'CLIENT_CREDENTIALS'
              ? 'Machine credentials (OAuth)'
              : value;
      field.append(option);
    }
    row.append(field);
    element.append(row);
    return field;
  }
  const name = input('Endpoint name'),
    endpoint = input('HTTPS MCP endpoint');
  const authentication = select('Authentication', [
    'API_KEY',
    'OAUTH_PKCE',
    'CLIENT_CREDENTIALS',
  ]);
  const header = select('API key header', [
    'Authorization',
    'X-API-Key',
    'Api-Key',
  ]);
  const oauthFields = {
    issuer: input('OAuth issuer'),
    resource: input('OAuth resource'),
    resourceMetadataUrl: input('Resource metadata URL'),
    issuerMetadataUrl: input('Issuer metadata URL'),
    authorizationEndpoint: input('Authorization endpoint'),
    tokenEndpoint: input('Token endpoint'),
    clientId: input('OAuth client ID'),
    redirectUri: input('OAuth redirect URI'),
    scopes: input('OAuth scopes (space separated)'),
  };
  function visibility() {
    if (header.parentElement)
      header.parentElement.hidden = authentication.value !== 'API_KEY';
    for (const field of Object.values(oauthFields))
      if (field.parentElement)
        field.parentElement.hidden = authentication.value === 'API_KEY';
  }
  authentication.addEventListener('change', visibility);
  function fill(value?: ProviderConnectionDefinition) {
    name.value = value?.name ?? '';
    endpoint.value = value?.endpoint ?? '';
    authentication.value = value?.authentication ?? 'API_KEY';
    header.value = value?.apiKeyHeader ?? 'Authorization';
    for (const key of Object.keys(
      oauthFields,
    ) as (keyof typeof oauthFields)[]) {
      const field = oauthFields[key];
      field.value =
        key === 'scopes'
          ? (value?.oauth?.scopes.join(' ') ?? '')
          : (value?.oauth?.[key] ?? '');
    }
    visibility();
  }
  fill();
  return {
    element,
    fill,
    writable(value: boolean) {
      for (const field of element.querySelectorAll('input, select')) {
        if (field instanceof HTMLInputElement) field.readOnly = !value;
        else if (field instanceof HTMLSelectElement) field.disabled = !value;
      }
    },
    read(): ProviderConnectionDefinition {
      const auth = authentication.value,
        key = header.value;
      if (
        auth !== 'API_KEY' &&
        auth !== 'OAUTH_PKCE' &&
        auth !== 'CLIENT_CREDENTIALS'
      )
        throw new Error('Select endpoint authentication');
      if (key !== 'Authorization' && key !== 'X-API-Key' && key !== 'Api-Key')
        throw new Error('Select API key header');
      const oauth: ProviderOAuthSettings | null =
        auth === 'API_KEY'
          ? null
          : {
              issuer: oauthFields.issuer.value,
              resource: oauthFields.resource.value,
              resourceMetadataUrl: oauthFields.resourceMetadataUrl.value,
              issuerMetadataUrl: oauthFields.issuerMetadataUrl.value,
              authorizationEndpoint: oauthFields.authorizationEndpoint.value,
              tokenEndpoint: oauthFields.tokenEndpoint.value,
              clientId: oauthFields.clientId.value,
              redirectUri: oauthFields.redirectUri.value,
              scopes: oauthFields.scopes.value.split(/\s+/u).filter(Boolean),
            };
      return {
        name: name.value,
        endpoint: endpoint.value,
        authentication: auth,
        apiKeyHeader: auth === 'API_KEY' ? key : null,
        oauth,
      };
    },
  };
}
