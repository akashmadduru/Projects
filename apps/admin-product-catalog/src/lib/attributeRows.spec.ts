import { describe, expect, it } from 'vitest';
import { rowsToAttrs } from './attributeRows';

describe('rowsToAttrs (TD-P7-19)', () => {
  it('keeps commas and equals signs inside values', () => {
    expect(rowsToAttrs([{ key: 'Size', value: '10,5' }, { key: 'Fit', value: 'a=b' }])).toEqual({ Size: '10,5', Fit: 'a=b' });
  });

  it('ignores fully blank rows and trims', () => {
    expect(rowsToAttrs([{ key: ' Color ', value: ' Red ' }, { key: '', value: '' }])).toEqual({ Color: 'Red' });
  });

  it('rejects a half-filled row or a repeated name', () => {
    expect(rowsToAttrs([{ key: 'Color', value: '' }])).toMatch(/both a name and a value/);
    expect(rowsToAttrs([{ key: 'Color', value: 'Red' }, { key: 'Color', value: 'Blue' }])).toMatch(/listed twice/);
  });
});
