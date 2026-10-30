"""Builds V149 (the all-India geography migration) from the India Post pincode directory CSV.

    git clone --depth 1 https://github.com/saravanakumargn/All-India-Pincode-Directory
    python3 -I scripts/geography/gen_india_geography.py \
        All-India-Pincode-Directory/all-india-pincode-html-csv.csv \
        src/main/resources/db/migration/V149__india_geography.sql

The version number is taken from the output file name. Maharashtra is skipped (V141 loaded it).
--check-mh <v141.json> compares this script's Maharashtra output with V141's, pincode by pincode.
"""
import collections
import csv
import hashlib
import json
import re
import sys

# CSV state -> (name we store, two-letter prefix, GST state code, other names the state may already have)
STATES = {
    'ANDAMAN & NICOBAR ISLANDS': ('Andaman and Nicobar Islands', 'AN', '35', ['Andaman & Nicobar Islands', 'Andaman and Nicobar']),
    'ANDHRA PRADESH': ('Andhra Pradesh', 'AP', '37', []),
    'ARUNACHAL PRADESH': ('Arunachal Pradesh', 'AR', '12', []),
    'ASSAM': ('Assam', 'AS', '18', []),
    'BIHAR': ('Bihar', 'BR', '10', []),
    'CHANDIGARH': ('Chandigarh', 'CH', '04', []),
    'CHATTISGARH': ('Chhattisgarh', 'CG', '22', ['Chattisgarh']),
    'DADRA & NAGAR HAVELI': ('Dadra and Nagar Haveli', 'DN', '26', ['Dadra & Nagar Haveli', 'Dadra and Nagar Haveli and Daman and Diu']),
    'DAMAN & DIU': ('Daman and Diu', 'DD', '26', ['Daman & Diu']),
    'DELHI': ('Delhi', 'DL', '07', ['New Delhi', 'NCT of Delhi']),
    'GOA': ('Goa', 'GA', '30', []),
    'GUJARAT': ('Gujarat', 'GJ', '24', []),
    'HARYANA': ('Haryana', 'HR', '06', []),
    'HIMACHAL PRADESH': ('Himachal Pradesh', 'HP', '02', []),
    'JAMMU & KASHMIR': ('Jammu and Kashmir', 'JK', '01', ['Jammu & Kashmir']),
    'JHARKHAND': ('Jharkhand', 'JH', '20', []),
    'KARNATAKA': ('Karnataka', 'KA', '29', []),
    'KERALA': ('Kerala', 'KL', '32', []),
    'LAKSHADWEEP': ('Lakshadweep', 'LD', '31', []),
    'MADHYA PRADESH': ('Madhya Pradesh', 'MP', '23', []),
    'MAHARASHTRA': ('Maharashtra', 'MH', '27', []),
    'MANIPUR': ('Manipur', 'MN', '14', []),
    'MEGHALAYA': ('Meghalaya', 'ML', '17', []),
    'MIZORAM': ('Mizoram', 'MZ', '15', []),
    'NAGALAND': ('Nagaland', 'NL', '13', []),
    'ODISHA': ('Odisha', 'OD', '21', ['Orissa']),
    'PONDICHERRY': ('Puducherry', 'PY', '34', ['Pondicherry']),
    'PUNJAB': ('Punjab', 'PB', '03', []),
    'RAJASTHAN': ('Rajasthan', 'RJ', '08', []),
    'SIKKIM': ('Sikkim', 'SK', '11', []),
    'TAMIL NADU': ('Tamil Nadu', 'TN', '33', []),
    'TELANGANA': ('Telangana', 'TS', '36', []),
    'TRIPURA': ('Tripura', 'TR', '16', []),
    'UTTAR PRADESH': ('Uttar Pradesh', 'UP', '09', []),
    'UTTARAKHAND': ('Uttarakhand', 'UK', '05', ['Uttaranchal']),
    'WEST BENGAL': ('West Bengal', 'WB', '19', []),
}
SKIP_STATES = {'MAHARASHTRA'}  # loaded by V141 (and corrected by V144)
TYPE_RANK = {'H.O': 0, 'S.O': 1, 'B.O directly a/w Head Office': 2, 'B.O': 3}
OFFICE_TAG = re.compile(r'\s+(?:B\.O|S\.O|H\.O|G\.P\.O)\.?(?=\s|\(|$)')
MISSING = {'', 'NA', 'NULL', 'N/A'}


def tidy(s):
    s = re.sub(r'\s+', ' ', s or '').strip()
    if s.isupper() and len(s) > 3:
        s = re.sub(r"[A-Za-z][A-Za-z']*", lambda m: m.group(0).capitalize(), s.lower())
    return s


def office(name):
    return re.sub(r'\s+', ' ', OFFICE_TAG.sub('', ' ' + name.strip())).strip()


def h(*parts, n=6):
    return hashlib.md5('|'.join(parts).encode()).hexdigest()[:n].upper()


def slug(s, n=30):
    return re.sub(r'[^A-Z0-9]+', '_', s.upper()).strip('_')[:n].strip('_')


def q(s):
    return "'" + s.replace('\\', '\\\\').replace("'", "''") + "'"


def build(rows):
    """pincode -> dict(state, district, city, areas[name...], primary)"""
    by_pin = collections.defaultdict(list)
    for r in rows:
        by_pin[r['pincode'].strip()].append(r)
    out = {}
    for pin, offices in sorted(by_pin.items()):
        if not re.fullmatch(r'\d{6}', pin):
            continue
        known = [o for o in offices if o['statename'] in STATES and o['Districtname'] not in MISSING]
        if not known:
            continue
        known.sort(key=lambda o: (TYPE_RANK.get(o['officeType'], 9), o['Deliverystatus'] != 'Delivery', office(o['officename']).lower()))
        prim = known[0]
        state, district = prim['statename'], tidy(prim['Districtname'])
        taluk = tidy(prim['Taluk'])
        if taluk in MISSING:
            same = [tidy(o['Taluk']) for o in known if o['statename'] == state and tidy(o['Districtname']) == district and tidy(o['Taluk']) not in MISSING]
            taluk = collections.Counter(same).most_common(1)[0][0] if same else district
        areas, seen = [], set()
        primary = office(prim['officename'])
        for o in sorted(offices, key=lambda o: office(o['officename']).lower()):
            a = office(o['officename'])[:150]
            if a and a.lower() not in seen:
                seen.add(a.lower())
                areas.append(a)
        out[pin] = {'state': state, 'district': district, 'city': taluk[:150], 'areas': areas, 'primary': primary}
    return out


def check_mh(pins, v141):
    same_city = same_primary = same_areas = 0
    common = [p for p in v141 if p in pins]
    for p in common:
        a, b = pins[p], v141[p]
        same_city += a['city'].lower() == b['city'].lower()
        same_primary += a['primary'].lower() == (b['primary'] or '').lower()
        same_areas += sorted(x.lower() for x in a['areas']) == sorted(x.lower() for x in b['areas'])
    n = len(common)
    print(f'MH check on {n} pincodes: city {same_city}/{n}, primary {same_primary}/{n}, areas {same_areas}/{n}')
    shown = 0
    for p in common:
        a, b = pins[p], v141[p]
        if a['primary'].lower() != (b['primary'] or '').lower() or sorted(x.lower() for x in a['areas']) != sorted(x.lower() for x in b['areas']):
            if shown < 6:
                print(' ', p, 'mine', a['primary'], a['areas'][:5], '| v141', b['primary'], b['areas'][:5])
                shown += 1


def emit(pins, path, version):
    desc = f'Imported from the India Post pincode directory (V{version})'
    states, districts, cities = {}, {}, {}
    area_rows = []
    for pin, p in pins.items():
        st = STATES[p['state']]
        sk = states.setdefault(p['state'], len(states) + 1)
        dk_key = (p['state'], p['district'].lower())
        if dk_key not in districts:
            districts[dk_key] = (len(districts) + 1, sk, p['district'], f"{st[1]}_{slug(p['district'])}_{h('district', st[0], p['district'])}")
        dk = districts[dk_key][0]
        ck_key = (dk, p['city'].lower())
        if ck_key not in cities:
            cities[ck_key] = (len(cities) + 1, dk, p['city'], f"{st[1]}_{slug(p['city'])}_{h('city', st[0], p['district'], p['city'])}")
        ck = cities[ck_key][0]
        for a in p['areas']:
            area_rows.append((pin, ck, a, a == p['primary']))
    # one primary per pincode, guaranteed by build(); make sure
    prim_count = collections.Counter(r[0] for r in area_rows if r[3])
    assert all(prim_count[p] == 1 for p in pins), 'primary per pincode'

    L = []
    w = L.append
    w('-- =============================================================================')
    w(f'-- V{version} — Geography for the rest of India: {len(states)} states and union territories,')
    w(f'-- {len(districts)} districts, {len(cities)} talukas (as cities), {len(area_rows)} post offices (as areas)')
    w(f'-- and {len(pins)} pincodes, in the platform\'s global masters. Maharashtra is not here: V141 loaded it.')
    w('--')
    w('-- Source: the India Post all-India pincode directory (data.gov.in), as mirrored on GitHub by')
    w('-- saravanakumargn/All-India-Pincode-Directory. Generated, do not edit.')
    w('--')
    w('-- Same rules as V141: a pincode\'s city is the taluka of its main post office (head office, then')
    w('-- sub office, then branch office), or the district when the directory gives no taluka; every post')
    w('-- office of the pincode is an area of that city, the main one the pincode\'s primary area.')
    w('-- Safe on a database that already has some of this: states (also under their older names, such as')
    w('-- Orissa or Pondicherry), districts and cities are looked up by name and reused; a pincode already')
    w('-- on file is left exactly as it is, with its areas; only missing pincodes are added.')
    w('-- Set-based: the directory is loaded into scratch tables (tmp_geo_*), merged, and the scratch')
    w('-- tables dropped at the end.')
    w('-- =============================================================================')
    w('')
    w("SET @p = UNHEX('00000000000000000000000000000001');")
    w('')
    w("INSERT INTO master_countries (id, company_id, code, name, status, display_order, iso_code2, iso_code3, dial_code, currency_code, created_at, updated_at, deleted, version)")
    w("SELECT UNHEX(REPLACE(UUID(), '-', '')), @p, 'INDIA_8E68B3', 'India', 'ACTIVE', 0, 'IN', 'IND', '+91', 'INR', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0")
    w("FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM master_countries WHERE company_id = @p AND name = 'India');")
    w("SET @country = (SELECT id FROM master_countries WHERE company_id = @p AND name = 'India' ORDER BY deleted, created_at LIMIT 1);")
    w('')
    w('DROP TABLE IF EXISTS tmp_geo_area, tmp_geo_city, tmp_geo_district, tmp_geo_state;')
    w('CREATE TABLE tmp_geo_state (k INT NOT NULL PRIMARY KEY, name VARCHAR(150) NOT NULL, names VARCHAR(500) NOT NULL, code VARCHAR(50) NOT NULL, gst VARCHAR(4) NULL, id BINARY(16) NULL) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;')
    w('CREATE TABLE tmp_geo_district (k INT NOT NULL PRIMARY KEY, state_k INT NOT NULL, name VARCHAR(150) NOT NULL, code VARCHAR(50) NOT NULL, id BINARY(16) NULL, KEY (state_k)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;')
    w('CREATE TABLE tmp_geo_city (k INT NOT NULL PRIMARY KEY, district_k INT NOT NULL, name VARCHAR(150) NOT NULL, code VARCHAR(50) NOT NULL, id BINARY(16) NULL, KEY (district_k)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;')
    w('CREATE TABLE tmp_geo_area (pincode CHAR(6) NOT NULL, city_k INT NOT NULL, name VARCHAR(150) NOT NULL, is_primary BOOLEAN NOT NULL, skip BOOLEAN NOT NULL DEFAULT FALSE, area_id BINARY(16) NULL, KEY (pincode), KEY (city_k, name)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;')
    w('')

    def chunks(table, cols, rows, size=2000):
        for i in range(0, len(rows), size):
            w(f'INSERT INTO {table} ({cols}) VALUES')
            w(',\n'.join(rows[i:i + size]) + ';')

    srows = []
    for csv_name, k in states.items():
        name, abbr, gst, aliases = STATES[csv_name]
        names = ','.join([name] + aliases)
        srows.append(f"({k}, {q(name)}, {q(names)}, {q(slug(name) + '_' + h('state', name))}, {q(gst)})")
    chunks('tmp_geo_state', 'k, name, names, code, gst', srows)
    chunks('tmp_geo_district', 'k, state_k, name, code', [f'({k}, {sk}, {q(n)}, {q(c)})' for k, sk, n, c in districts.values()])
    chunks('tmp_geo_city', 'k, district_k, name, code', [f'({k}, {dk}, {q(n)}, {q(c)})' for k, dk, n, c in cities.values()])
    chunks('tmp_geo_area', 'pincode, city_k, name, is_primary', [f"('{p}', {ck}, {q(a)}, {1 if pr else 0})" for p, ck, a, pr in area_rows], 4000)
    w('')
    w('''-- States: reuse one already on file under its name or an older name, else add it.
UPDATE tmp_geo_state t JOIN master_states s ON s.company_id = @p AND s.country_id = @country AND s.name = t.name SET t.id = s.id;
UPDATE tmp_geo_state t SET t.id = (SELECT s.id FROM master_states s WHERE s.company_id = @p AND s.country_id = @country
    AND FIND_IN_SET(UPPER(s.name), UPPER(t.names)) > 0 ORDER BY s.deleted, s.created_at LIMIT 1) WHERE t.id IS NULL;
INSERT INTO master_states (id, company_id, code, name, status, display_order, country_id, gst_state_code, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @p, t.code, t.name, 'ACTIVE', 0, @country, t.gst, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM tmp_geo_state t WHERE t.id IS NULL AND NOT EXISTS (SELECT 1 FROM master_states s WHERE s.company_id = @p AND s.code = t.code);
UPDATE tmp_geo_state t JOIN master_states s ON s.company_id = @p AND s.country_id = @country AND s.name = t.name SET t.id = s.id WHERE t.id IS NULL;

-- Districts, then cities: reuse by name within the parent, else add.
UPDATE tmp_geo_district t JOIN tmp_geo_state st ON st.k = t.state_k
  JOIN master_districts d ON d.company_id = @p AND d.state_id = st.id AND d.name = t.name SET t.id = d.id;
INSERT INTO master_districts (id, company_id, code, name, status, display_order, state_id, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @p, t.code, t.name, 'ACTIVE', 0, st.id, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM tmp_geo_district t JOIN tmp_geo_state st ON st.k = t.state_k
WHERE t.id IS NULL AND st.id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM master_districts d WHERE d.company_id = @p AND d.code = t.code);
UPDATE tmp_geo_district t JOIN tmp_geo_state st ON st.k = t.state_k
  JOIN master_districts d ON d.company_id = @p AND d.state_id = st.id AND d.name = t.name SET t.id = d.id WHERE t.id IS NULL;

UPDATE tmp_geo_city t JOIN tmp_geo_district dt ON dt.k = t.district_k
  JOIN master_cities c ON c.company_id = @p AND c.district_id = dt.id AND c.name = t.name SET t.id = c.id;
INSERT INTO master_cities (id, company_id, code, name, status, display_order, district_id, is_metro, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @p, t.code, t.name, 'ACTIVE', 0, dt.id, FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM tmp_geo_city t JOIN tmp_geo_district dt ON dt.k = t.district_k
WHERE t.id IS NULL AND dt.id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM master_cities c WHERE c.company_id = @p AND c.code = t.code);
UPDATE tmp_geo_city t JOIN tmp_geo_district dt ON dt.k = t.district_k
  JOIN master_cities c ON c.company_id = @p AND c.district_id = dt.id AND c.name = t.name SET t.id = c.id WHERE t.id IS NULL;

-- A pincode already on file keeps everything it has.
UPDATE tmp_geo_area t JOIN master_pincodes pin ON pin.company_id = @p AND pin.code = t.pincode SET t.skip = TRUE;

-- Areas: one per city and name (two pincodes of one taluka may share a post office name).
INSERT INTO master_areas (id, company_id, code, name, status, display_order, city_id, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @p, CONCAT('A', x.pincode, '_', UPPER(LEFT(MD5(CONCAT(x.pincode, '|', x.name)), 8))), x.name, 'ACTIVE', 0, x.city_id,
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT c.id city_id, MIN(t.name) name, MIN(t.pincode) pincode
      FROM tmp_geo_area t JOIN tmp_geo_city c ON c.k = t.city_k
      WHERE t.skip = FALSE AND c.id IS NOT NULL GROUP BY c.id, t.name) x
WHERE NOT EXISTS (SELECT 1 FROM master_areas a WHERE a.company_id = @p AND a.city_id = x.city_id AND a.name = x.name)
  AND NOT EXISTS (SELECT 1 FROM master_areas a WHERE a.company_id = @p AND a.code = CONCAT('A', x.pincode, '_', UPPER(LEFT(MD5(CONCAT(x.pincode, '|', x.name)), 8))));
UPDATE tmp_geo_area t JOIN tmp_geo_city c ON c.k = t.city_k
  JOIN master_areas a ON a.company_id = @p AND a.city_id = c.id AND a.name = t.name SET t.area_id = a.id WHERE t.skip = FALSE;

-- Pincodes, routed to their primary area, then every post office of the pincode as its areas.
INSERT INTO master_pincodes (id, company_id, code, name, description, status, display_order, area_id, serviceable, cod_available, prepaid_available, pickup_available, oda_applicable, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @p, t.pincode, t.name, ''' + q(desc) + ''', 'ACTIVE', 0, t.area_id, TRUE, TRUE, TRUE, TRUE, FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM tmp_geo_area t
WHERE t.is_primary = TRUE AND t.skip = FALSE AND t.area_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM master_pincodes pin WHERE pin.company_id = @p AND pin.code = t.pincode);
INSERT INTO master_pincode_areas (id, company_id, pincode_id, area_id, is_primary, oda_applicable, created_at, updated_at, deleted, version)
SELECT UNHEX(REPLACE(UUID(), '-', '')), @p, x.pincode_id, x.area_id, x.area_id = x.primary_area_id, FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), FALSE, 0
FROM (SELECT DISTINCT pin.id pincode_id, t.area_id, pin.area_id primary_area_id
      FROM tmp_geo_area t JOIN master_pincodes pin ON pin.company_id = @p AND pin.code = t.pincode AND pin.description = ''' + q(desc) + '''
      WHERE t.skip = FALSE AND t.area_id IS NOT NULL) x
WHERE NOT EXISTS (SELECT 1 FROM master_pincode_areas pa WHERE pa.company_id = @p AND pa.pincode_id = x.pincode_id AND pa.area_id = x.area_id);

DROP TABLE tmp_geo_area, tmp_geo_city, tmp_geo_district, tmp_geo_state;''')
    open(path, 'w', encoding='utf-8').write('\n'.join(L) + '\n')
    print(f'wrote {path}: states {len(states)}, districts {len(districts)}, cities {len(cities)}, areas {len(area_rows)}, pincodes {len(pins)}')


if __name__ == '__main__':
    src, out = sys.argv[1], sys.argv[2]
    rows = list(csv.DictReader(open(src, encoding='utf-8', errors='replace')))
    pins = build(rows)
    if '--check-mh' in sys.argv:
        v141 = json.load(open(sys.argv[sys.argv.index('--check-mh') + 1]))
        check_mh({p: v for p, v in pins.items() if v['state'] == 'MAHARASHTRA'}, v141)
    version = re.search(r'V(\d+)__', out).group(1)
    emit({p: v for p, v in pins.items() if v['state'] not in SKIP_STATES}, out, version)
