# Sample for the CSV employee import

Two files that work together, for trying **Employees → Import CSV** in the admin
portal:

| File | What it is |
|---|---|
| [`../employees-import-template.csv`](../employees-import-template.csv) | One employee row, with the column names the import expects |
| `E-1001.jpg` | The ID/photo that row's `photoFile` column names |

In the Import popup, choose the CSV under **CSV file** and `E-1001.jpg` under
**ID / photos**. The import pairs them by file name, so the picture has to keep
the name the `photoFile` column gives it.

## About the picture

It is the **California specimen driver licence** published on Wikimedia Commons
([file page](https://commons.wikimedia.org/wiki/File:Californian_sample_driver%27s_license,_c._2019.jpg)),
in the public domain as a work of the State of California. It is a specimen, not
anybody's licence: the card itself says SAMPLE, and the holder is "Ima
Cardholder" of Anytown. The template's row uses that same name on purpose, so
the record and the card it was created from agree.

Using a whole ID card rather than a cropped headshot is deliberate — "ID /
photo" is exactly what an administrator uploads in this system.

## What this sample does not prove

A successful import only means the record and the picture were stored. It does
**not** mean that face will enrol: that happens later, when the employee's first
live scan is compared against this image, and it is judged by Rekognition on the
server. For a demo that goes all the way through to a MATCH, the reference has to
be a photo of the person who will actually sit in front of the camera.
