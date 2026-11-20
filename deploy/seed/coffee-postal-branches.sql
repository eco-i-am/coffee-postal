-- Coffee Postal PH branches (kiosk, flagship, express). Safe to run again: a branch whose code already exists is left alone.
-- Codes are the store code from the franchise list with a type prefix: K- kiosk, X- express. Two store codes are shared
-- (7100 and 6014), which is why the prefix, and -A / -B for the two Mandaue stores, are there.
BEGIN;

INSERT INTO branches (code, name, address)
SELECT v.code, v.name, v.address
FROM (VALUES
  -- Kiosks
  ('K-4217', 'Coffee Postal Kiosk - Lipa', 'Lipa, Batangas'),
  ('K-7100', 'Coffee Postal Kiosk - Dipolog', 'Dipolog City'),
  ('K-1485', 'Coffee Postal Kiosk - Navotas', 'Navotas City'),
  ('K-8000', 'Coffee Postal Kiosk - Davao', 'Davao City'),
  ('K-4234', 'Coffee Postal Kiosk - Sto. Tomas', 'Sto. Tomas, Batangas'),
  ('K-6340', 'Coffee Postal Kiosk - Premium Bohol', 'Bohol'),
  ('FLAGSHIP', 'Coffee Postal Flagship Store', NULL),
  -- Express
  ('X-5600', 'Coffee Postal Express - Kalibo', 'Kalibo, Aklan'),
  ('X-6337', 'Coffee Postal Express - Corella', 'Corella, Bohol'),
  ('X-6040', 'Coffee Postal Express - Aloguinsan', 'Aloguinsan, Cebu'),
  ('X-6015', 'Coffee Postal Express - Bankal', 'Bankal, Lapu-lapu'),
  ('X-3500', 'Coffee Postal Express - Tuguegarao', 'Tuguegarao City'),
  ('X-4102', 'Coffee Postal Express - Bacoor', 'Bacoor, Cavite'),
  ('X-3000', 'Coffee Postal Express - Mojon', 'Mojon, Malolos'),
  ('X-6107', 'Coffee Postal Express - Binalbagan', 'Binalbagan, Negros Occidental'),
  ('X-7100', 'Coffee Postal Express - Sta. Isabel', 'Sta. Isabel, Dipolog City'),
  ('X-9200', 'Coffee Postal Express - Iligan', 'Iligan City'),
  ('X-6200', 'Coffee Postal Express - Dumaguete', 'Dumaguete, Negros Oriental'),
  ('X-6215', 'Coffee Postal Express - Valencia', 'Valencia, Negros Oriental'),
  ('X-6046', 'Coffee Postal Express - Minglanilla', 'Minglanilla, Cebu'),
  ('X-4905', 'Coffee Postal Express - Gasan', 'Gasan, Marinduque'),
  ('X-6022', 'Coffee Postal Express - Dalaguete', 'Dalaguete, Cebu'),
  ('X-1008', 'Coffee Postal Express - Sampaloc', 'Sampaloc, Manila'),
  ('X-6014-A', 'Coffee Postal Express - Looc & Centro, Mandaue', 'Looc & Centro, Mandaue, Cebu'),
  ('X-6014-B', 'Coffee Postal Express - Tabok, Mandaue', 'Tabok, Mandaue, Cebu'),
  ('X-1970', 'Coffee Postal Express - Sta. Cruz', 'Sta. Cruz, Laguna'),
  ('X-6006', 'Coffee Postal Express - Catmon', 'Catmon, Cebu'),
  ('X-6541', 'Coffee Postal Express - Ormoc', 'Ormoc City'),
  ('X-6000', 'Coffee Postal Express - Cebu City', 'Cebu City'),
  ('X-6039', 'Coffee Postal Express - Pinamungajan', 'Pinamungajan, Cebu'),
  ('X-3103', 'Coffee Postal Express - Peñaranda', 'Peñaranda, Nueva Ecija')
) AS v(code, name, address)
WHERE NOT EXISTS (SELECT 1 FROM branches b WHERE b.code = v.code);

COMMIT;
